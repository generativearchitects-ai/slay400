package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Bytes written are read back to the same wire values, for random layouts (scalars of every kind, dates, times and
 * timestamps in every accepted format and precision, structs, arrays, nested)
 * and random values that fit them. In-house generator on seeded {@link Random} (decision 48): the run seed comes from
 * {@code -Dslay400.seed} or is random, each case is seeded from it and its index, and a failure names both. The number of
 * cases is {@code -Dslay400.cases}, 500 by default.
 */
class LayoutPropertyTest {

    static final String TEXT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,-/()&*%+=<>?:;'";
    static final int[] CCSIDS = {37, 273, 277, 278, 280, 284, 285, 297, 500, 871, 1140};

    record Case(Layout layout, WireValue value, WireValue expected) {
    }

    static final class Generator {
        final Random random;
        int fields;

        Generator(long seed) {
            random = new Random(seed);
        }

        Layout layout(String path, int depth) {
            fields++;
            int[] leaves = {0, 1, 2, 3, 4, 5, 6, 9, 10, 11};
            int kind = depth > 2 || fields > 40 ? leaves[random.nextInt(leaves.length)] : random.nextInt(12);
            return switch (kind) {
                case 11 -> new Layout.Timestamp(path, random.nextInt(13));
                case 9 -> new Layout.Date(path, Temporal.DateFormat.values()[random.nextInt(Temporal.DateFormat.values().length)], separator());
                case 10 -> new Layout.Time(path, Temporal.TimeFormat.values()[random.nextInt(Temporal.TimeFormat.values().length)], separator());
                case 0 -> new Layout.Text(path, 1 + random.nextInt(40), Optional.empty());
                case 1 -> new Layout.VarText(path, 1 + random.nextInt(30), random.nextBoolean() ? 2 : 4, Optional.empty());
                case 2 -> new Layout.Int(path, new int[] {2, 4, 8}[random.nextInt(3)], random.nextBoolean());
                case 3, 4 -> {
                    int digits = 1 + random.nextInt(random.nextInt(4) == 0 ? 63 : 15);
                    yield new Layout.Decimal(path, digits, random.nextInt(digits + 1), kind == 3);
                }
                case 5 -> new Layout.Float(path, random.nextBoolean() ? 4 : 8);
                case 6 -> new Layout.Bytes(path, 1 + random.nextInt(16));
                case 7 -> {
                    List<Layout> members = new ArrayList<>();
                    int count = 1 + random.nextInt(5);
                    for (int i = 0; i < count; i++) {
                        members.add(layout(path + ".M" + i, depth + 1));
                    }
                    yield new Layout.Struct(path, members);
                }
                case 8 -> new Layout.Array(path, random.nextInt(5), layout(path, depth + 1));
                default -> throw new IllegalStateException("kind " + kind);
            };
        }

        /** A value that fits the layout, and what reading its bytes gives back. */
        WireValue[] value(Layout layout) {
            if (random.nextInt(12) == 0) {
                return new WireValue[] {new WireValue.Blank(), blank(layout)};
            }
            return switch (layout) {
                case Layout.Text t -> {
                    String text = text(random.nextInt(t.length() + 1));
                    yield new WireValue[] {new WireValue.Text(text), new WireValue.Text(text + " ".repeat(t.length() - text.length()))};
                }
                case Layout.VarText v -> {
                    WireValue text = new WireValue.Text(text(random.nextInt(v.maxLength() + 1)));
                    yield new WireValue[] {text, text};
                }
                case Layout.Int i -> {
                    BigInteger range = i.max().subtract(i.min()).add(BigInteger.ONE);
                    BigInteger value = switch (random.nextInt(4)) {
                        case 0 -> i.min();
                        case 1 -> i.max();
                        default -> i.min().add(new BigInteger(range.bitLength() + 8, random).mod(range));
                    };
                    yield twice(new WireValue.Integer(value));
                }
                case Layout.Decimal d -> {
                    StringBuilder digits = new StringBuilder();
                    for (int k = 0; k < d.digits(); k++) {
                        digits.append(random.nextInt(4) == 0 ? '9' : (char) ('0' + random.nextInt(10)));
                    }
                    BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), d.scale());
                    yield twice(new WireValue.Decimal(random.nextBoolean() ? value.negate() : value));
                }
                case Layout.Float f -> {
                    double value = f.bytes() == 4 ? (double) (random.nextFloat() * 2e6f - 1e6f) : random.nextDouble() * 2e15 - 1e15;
                    yield twice(new WireValue.Float(value));
                }
                case Layout.Date d -> {
                    long from = d.format().lowest().toEpochDay();
                    long to = d.format().highest().toEpochDay();
                    long day = switch (random.nextInt(4)) {
                        case 0 -> from;
                        case 1 -> to;
                        default -> from + (long) (random.nextDouble() * (to - from + 1));
                    };
                    yield twice(new WireValue.Date(java.time.LocalDate.ofEpochDay(Math.min(day, to))));
                }
                case Layout.Time t -> {
                    int seconds = t.format() == Temporal.TimeFormat.USA ? 0 : random.nextInt(60);
                    yield twice(new WireValue.Time(java.time.LocalTime.of(random.nextInt(24), random.nextInt(60), seconds)));
                }
                case Layout.Timestamp t -> {
                    java.time.LocalDate day = java.time.LocalDate.ofEpochDay(java.time.LocalDate.of(1, 1, 1).toEpochDay()
                            + (long) (random.nextDouble() * (java.time.LocalDate.of(9999, 12, 31).toEpochDay() - java.time.LocalDate.of(1, 1, 1).toEpochDay())));
                    int shown = Math.min(t.digits(), 9);
                    int nanos = random.nextInt((int) Math.pow(10, shown)) * (int) Math.pow(10, 9 - shown);
                    yield twice(new WireValue.Timestamp(day.atTime(random.nextInt(24), random.nextInt(60), random.nextInt(60), nanos)));
                }
                case Layout.Bytes b -> {
                    byte[] bytes = new byte[random.nextInt(b.length() + 1)];
                    random.nextBytes(bytes);
                    byte[] padded = new byte[b.length()];
                    System.arraycopy(bytes, 0, padded, 0, bytes.length);
                    yield new WireValue[] {new WireValue.Bytes(bytes), new WireValue.Bytes(padded)};
                }
                case Layout.Struct s -> {
                    List<WireValue> values = new ArrayList<>();
                    List<WireValue> expected = new ArrayList<>();
                    for (Layout member : s.members()) {
                        WireValue[] pair = value(member);
                        values.add(pair[0]);
                        expected.add(pair[1]);
                    }
                    yield new WireValue[] {new WireValue.Struct(values), new WireValue.Struct(expected)};
                }
                case Layout.Array a -> {
                    List<WireValue> values = new ArrayList<>();
                    List<WireValue> expected = new ArrayList<>();
                    for (int k = 0; k < a.count(); k++) {
                        WireValue[] pair = value(a.element());
                        values.add(pair[0]);
                        expected.add(pair[1]);
                    }
                    yield new WireValue[] {new WireValue.Array(values), new WireValue.Array(expected)};
                }
            };
        }

        /** What a blank reads back as. */
        static WireValue blank(Layout layout) {
            return switch (layout) {
                case Layout.Text t -> new WireValue.Text(" ".repeat(t.length()));
                case Layout.VarText v -> new WireValue.Text("");
                case Layout.Int i -> new WireValue.Integer(BigInteger.ZERO);
                case Layout.Decimal d -> new WireValue.Decimal(BigDecimal.ZERO.setScale(d.scale()));
                case Layout.Float f -> new WireValue.Float(0);
                case Layout.Bytes b -> new WireValue.Bytes(new byte[b.length()]);
                case Layout.Date d -> new WireValue.Date(d.format().lowest());
                case Layout.Timestamp t -> new WireValue.Timestamp(java.time.LocalDateTime.of(1, 1, 1, 0, 0));
                case Layout.Time t -> new WireValue.Time(java.time.LocalTime.MIDNIGHT);
                case Layout.Struct s -> new WireValue.Struct(s.members().stream().map(Generator::blank).toList());
                case Layout.Array a -> new WireValue.Array(java.util.Collections.nCopies(a.count(), blank(a.element())));
            };
        }

        char separator() {
            return new char[] {'-', '/', '.', ':'}[random.nextInt(4)];
        }

        static WireValue[] twice(WireValue value) {
            return new WireValue[] {value, value};
        }

        String text(int length) {
            StringBuilder out = new StringBuilder();
            for (int k = 0; k < length; k++) {
                out.append(TEXT.charAt(random.nextInt(TEXT.length())));
            }
            return out.toString();
        }
    }

    @Test
    void bytesWrittenAreReadBackToTheSameWireValues() {
        long seed = Long.getLong("slay400.seed", new Random().nextLong());
        int cases = Integer.getInteger("slay400.cases", 500);
        for (int index = 0; index < cases; index++) {
            Generator generator = new Generator(seed * 31 + index);
            Layout layout = generator.layout("P", 0);
            WireValue[] pair = generator.value(layout);
            int ccsid = CCSIDS[generator.random.nextInt(CCSIDS.length)];
            String where = "seed " + seed + ", case " + index + " (rerun with -Dslay400.seed=" + seed + "), CCSID " + ccsid;
            byte[] bytes = layout.toBytes(pair[0], ccsid);
            assertThat(bytes).as(where).hasSize(layout.size());
            assertThat(layout.read(bytes, 0, ccsid)).as(where).isEqualTo(pair[1]);
            // Reading at an offset inside a larger buffer gives the same.
            byte[] wider = new byte[bytes.length + 3];
            System.arraycopy(bytes, 0, wider, 2, bytes.length);
            assertThat(layout.read(wider, 2, ccsid)).as(where).isEqualTo(pair[1]);
            // And what was read writes the same bytes again.
            assertThat(layout.toBytes(pair[1], ccsid)).as(where).isEqualTo(bytes);
        }
    }
}
