package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.pcml.codec.WireValue;
import com.ibm.as400.access.AS400Bin2;
import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400Bin8;
import com.ibm.as400.access.AS400Float4;
import com.ibm.as400.access.AS400Float8;
import com.ibm.as400.access.AS400PackedDecimal;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.AS400UnsignedBin2;
import com.ibm.as400.access.AS400UnsignedBin4;
import com.ibm.as400.access.AS400UnsignedBin8;
import com.ibm.as400.access.AS400ZonedDecimal;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The byte layout of a PCML field, as a program receives it: character data in a CCSID, packed and zoned decimals, integers,
 * floats and bytes; a struct member after member, an array element after element, always at the full declared size. Values
 * are converted with JT400's data-type classes.
 *
 * <p>Nothing is changed silently (decision 40): a character the CCSID cannot hold, a number that does not fit, or bytes that
 * are not a valid number are a {@link LayoutException}, whose message names the field and never the value.
 *
 * <p>A {@link WireValue.Blank} is written as the empty value of the layout: blanks for character data, zero for numbers and
 * zero bytes for bytes.
 */
public sealed interface Layout {

    /** The size of the field in bytes. */
    int size();

    /** The path of the field, as in PCML: {@code ORDER.LINES.SKU}. */
    String path();

    /**
     * Writes a value at {@code offset}.
     *
     * @param ccsid the CCSID of character data that names none of its own: that of the connection's job
     * @throws LayoutException if the value does not fit the layout
     */
    void write(WireValue value, byte[] into, int offset, int ccsid);

    /**
     * Reads the value at {@code offset}.
     *
     * @param ccsid the CCSID of character data that names none of its own
     * @throws LayoutException if the bytes are not a value of the layout
     */
    WireValue read(byte[] from, int offset, int ccsid);

    /** The bytes of a value, at the size of the layout. */
    default byte[] toBytes(WireValue value, int ccsid) {
        byte[] bytes = new byte[size()];
        write(value, bytes, 0, ccsid);
        return bytes;
    }

    /**
     * Character data of a fixed length in a single-byte CCSID.
     *
     * @param path   the field
     * @param length the length in characters, which is the length in bytes
     * @param ccsid  the CCSID of the field, if the PCML names one
     */
    record Text(String path, int length, Optional<Integer> ccsid) implements Layout {
        /** @throws NullPointerException if an argument is {@code null} */
        public Text {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(ccsid, "ccsid");
        }

        @Override
        public int size() {
            return length;
        }

        private AS400Text type(int jobCcsid) {
            return new AS400Text(length, ccsid.orElse(jobCcsid));
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int jobCcsid) {
            String text = switch (value) {
                case WireValue.Text t -> t.value();
                case WireValue.Blank b -> "";
                default -> throw LayoutException.wrongKind(path, "text");
            };
            if (text.length() > length) {
                throw new LayoutException(path, "the text is longer than the field");
            }
            AS400Text type = type(jobCcsid);
            byte[] bytes;
            String back;
            try {
                bytes = type.toBytes(text);
                back = (String) type.toObject(bytes);
            } catch (RuntimeException e) {
                throw new LayoutException(path, "the text cannot be converted to the CCSID of the field");
            }
            // A character the CCSID cannot hold comes back as a substitute: refuse it rather than send something else.
            if (!rtrim(back).equals(rtrim(text))) {
                throw new LayoutException(path, "the text has a character that the CCSID of the field cannot hold");
            }
            System.arraycopy(bytes, 0, into, offset, length);
        }

        @Override
        public WireValue read(byte[] from, int offset, int jobCcsid) {
            try {
                return new WireValue.Text((String) type(jobCcsid).toObject(from, offset));
            } catch (RuntimeException e) {
                throw new LayoutException(path, "the bytes cannot be converted from the CCSID of the field");
            }
        }

        static String rtrim(String text) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') {
                end--;
            }
            return text.substring(0, end);
        }
    }

    /**
     * Character data preceded by its length in a 2- or 4-byte prefix; the field always takes its maximum size.
     *
     * @param path        the field
     * @param maxLength   the maximum length in characters
     * @param prefixBytes the size of the length prefix, 2 or 4
     * @param ccsid       the CCSID of the field, if the PCML names one
     */
    record VarText(String path, int maxLength, int prefixBytes, Optional<Integer> ccsid) implements Layout {
        /**
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code prefixBytes} is not 2 or 4
         */
        public VarText {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(ccsid, "ccsid");
            if (prefixBytes != 2 && prefixBytes != 4) {
                throw new IllegalArgumentException("prefixBytes must be 2 or 4");
            }
        }

        @Override
        public int size() {
            return prefixBytes + maxLength;
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int jobCcsid) {
            String text = switch (value) {
                case WireValue.Text t -> t.value();
                case WireValue.Blank b -> "";
                default -> throw LayoutException.wrongKind(path, "text");
            };
            if (text.length() > maxLength) {
                throw new LayoutException(path, "the text is longer than the field");
            }
            Arrays.fill(into, offset, offset + size(), (byte) 0);
            if (prefixBytes == 2) {
                new AS400UnsignedBin2().toBytes(text.length(), into, offset);
            } else {
                new AS400Bin4().toBytes(text.length(), into, offset);
            }
            if (!text.isEmpty()) {
                new Text(path, text.length(), ccsid).write(new WireValue.Text(text), into, offset + prefixBytes, jobCcsid);
            }
        }

        @Override
        public WireValue read(byte[] from, int offset, int jobCcsid) {
            long length = prefixBytes == 2 ? ((Number) new AS400UnsignedBin2().toObject(from, offset)).longValue()
                    : ((Number) new AS400Bin4().toObject(from, offset)).longValue();
            if (length < 0 || length > maxLength) {
                throw new LayoutException(path, "the length prefix is outside the field");
            }
            if (length == 0) {
                return new WireValue.Text("");
            }
            return new Text(path, (int) length, ccsid).read(from, offset + prefixBytes, jobCcsid);
        }
    }

    /**
     * A binary integer.
     *
     * @param path   the field
     * @param bytes  the size, 2, 4 or 8 bytes
     * @param signed whether the integer is signed
     */
    record Int(String path, int bytes, boolean signed) implements Layout {
        /**
         * @throws NullPointerException     if {@code path} is {@code null}
         * @throws IllegalArgumentException if {@code bytes} is not 2, 4 or 8
         */
        public Int {
            Objects.requireNonNull(path, "path");
            if (bytes != 2 && bytes != 4 && bytes != 8) {
                throw new IllegalArgumentException("bytes must be 2, 4 or 8");
            }
        }

        @Override
        public int size() {
            return bytes;
        }

        /** The smallest value of the field. */
        BigInteger min() {
            return signed ? BigInteger.ONE.shiftLeft(bytes * 8 - 1).negate() : BigInteger.ZERO;
        }

        /** The largest value of the field. */
        BigInteger max() {
            return signed ? BigInteger.ONE.shiftLeft(bytes * 8 - 1).subtract(BigInteger.ONE) : BigInteger.ONE.shiftLeft(bytes * 8).subtract(BigInteger.ONE);
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int ccsid) {
            BigInteger number = switch (value) {
                case WireValue.Integer i -> i.value();
                case WireValue.Blank b -> BigInteger.ZERO;
                default -> throw LayoutException.wrongKind(path, "a whole number");
            };
            if (number.compareTo(min()) < 0 || number.compareTo(max()) > 0) {
                throw new LayoutException(path, "the number does not fit the field");
            }
            if (signed) {
                switch (bytes) {
                    case 2 -> new AS400Bin2().toBytes(number.shortValueExact(), into, offset);
                    case 4 -> new AS400Bin4().toBytes(number.intValueExact(), into, offset);
                    default -> new AS400Bin8().toBytes(number.longValueExact(), into, offset);
                }
            } else {
                switch (bytes) {
                    case 2 -> new AS400UnsignedBin2().toBytes(number.intValueExact(), into, offset);
                    case 4 -> new AS400UnsignedBin4().toBytes(number.longValueExact(), into, offset);
                    default -> new AS400UnsignedBin8().toBytes(number, into, offset);
                }
            }
        }

        @Override
        public WireValue read(byte[] from, int offset, int ccsid) {
            Object number;
            if (signed) {
                number = switch (bytes) {
                    case 2 -> new AS400Bin2().toObject(from, offset);
                    case 4 -> new AS400Bin4().toObject(from, offset);
                    default -> new AS400Bin8().toObject(from, offset);
                };
            } else {
                number = switch (bytes) {
                    case 2 -> new AS400UnsignedBin2().toObject(from, offset);
                    case 4 -> new AS400UnsignedBin4().toObject(from, offset);
                    default -> new AS400UnsignedBin8().toObject(from, offset);
                };
            }
            return new WireValue.Integer(number instanceof BigInteger big ? big : BigInteger.valueOf(((Number) number).longValue()));
        }
    }

    /**
     * A packed or zoned decimal.
     *
     * @param path   the field
     * @param digits the number of digits, 1 to 63
     * @param scale  how many of them follow the decimal point
     * @param packed {@code true} for packed, {@code false} for zoned
     */
    record Decimal(String path, int digits, int scale, boolean packed) implements Layout {
        /**
         * @throws NullPointerException     if {@code path} is {@code null}
         * @throws IllegalArgumentException if {@code digits} is not 1 to 63 or {@code scale} not 0 to {@code digits}
         */
        public Decimal {
            Objects.requireNonNull(path, "path");
            if (digits < 1 || digits > 63 || scale < 0 || scale > digits) {
                throw new IllegalArgumentException("digits must be 1 to 63 and scale 0 to digits");
            }
        }

        @Override
        public int size() {
            return packed ? digits / 2 + 1 : digits;
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int ccsid) {
            BigDecimal number = switch (value) {
                case WireValue.Decimal d -> d.value();
                case WireValue.Blank b -> BigDecimal.ZERO;
                default -> throw LayoutException.wrongKind(path, "a decimal");
            };
            BigDecimal exact;
            try {
                exact = number.setScale(scale);
            } catch (ArithmeticException e) {
                throw new LayoutException(path, "the number has more decimals than the field");
            }
            if (exact.signum() != 0 && exact.precision() - exact.scale() > digits - scale) {
                throw new LayoutException(path, "the number does not fit the field");
            }
            try {
                if (packed) {
                    new AS400PackedDecimal(digits, scale).toBytes(exact, into, offset);
                } else {
                    new AS400ZonedDecimal(digits, scale).toBytes(exact, into, offset);
                }
            } catch (RuntimeException e) {
                throw new LayoutException(path, "the number does not fit the field");
            }
        }

        @Override
        public WireValue read(byte[] from, int offset, int ccsid) {
            try {
                Object number = packed ? new AS400PackedDecimal(digits, scale).toObject(from, offset) : new AS400ZonedDecimal(digits, scale).toObject(from, offset);
                return new WireValue.Decimal(((BigDecimal) number).setScale(scale));
            } catch (RuntimeException e) {
                throw new LayoutException(path, packed ? "the bytes are not a valid packed number" : "the bytes are not a valid zoned number");
            }
        }
    }

    /**
     * A binary floating-point number.
     *
     * @param path  the field
     * @param bytes the size, 4 or 8 bytes
     */
    record Float(String path, int bytes) implements Layout {
        /**
         * @throws NullPointerException     if {@code path} is {@code null}
         * @throws IllegalArgumentException if {@code bytes} is not 4 or 8
         */
        public Float {
            Objects.requireNonNull(path, "path");
            if (bytes != 4 && bytes != 8) {
                throw new IllegalArgumentException("bytes must be 4 or 8");
            }
        }

        @Override
        public int size() {
            return bytes;
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int ccsid) {
            double number = switch (value) {
                case WireValue.Float f -> f.value();
                case WireValue.Blank b -> 0;
                default -> throw LayoutException.wrongKind(path, "a floating-point number");
            };
            if (!Double.isFinite(number) || bytes == 4 && Math.abs(number) > java.lang.Float.MAX_VALUE) {
                throw new LayoutException(path, "the number does not fit the field");
            }
            if (bytes == 4) {
                new AS400Float4().toBytes((float) number, into, offset);
            } else {
                new AS400Float8().toBytes(number, into, offset);
            }
        }

        @Override
        public WireValue read(byte[] from, int offset, int ccsid) {
            Object number = bytes == 4 ? new AS400Float4().toObject(from, offset) : new AS400Float8().toObject(from, offset);
            return new WireValue.Float(((Number) number).doubleValue());
        }
    }

    /**
     * Bytes of a fixed length; a shorter value is followed by zero bytes.
     *
     * @param path   the field
     * @param length the length in bytes
     */
    record Bytes(String path, int length) implements Layout {
        /** @throws NullPointerException if {@code path} is {@code null} */
        public Bytes {
            Objects.requireNonNull(path, "path");
        }

        @Override
        public int size() {
            return length;
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int ccsid) {
            byte[] bytes = switch (value) {
                case WireValue.Bytes b -> b.value();
                case WireValue.Blank b -> new byte[0];
                default -> throw LayoutException.wrongKind(path, "bytes");
            };
            if (bytes.length > length) {
                throw new LayoutException(path, "the bytes are longer than the field");
            }
            Arrays.fill(into, offset, offset + length, (byte) 0);
            System.arraycopy(bytes, 0, into, offset, bytes.length);
        }

        @Override
        public WireValue read(byte[] from, int offset, int ccsid) {
            return new WireValue.Bytes(Arrays.copyOfRange(from, offset, offset + length));
        }
    }

    /**
     * A struct: its members one after the other, without alignment.
     *
     * @param path    the field
     * @param members the members, in PCML order
     */
    record Struct(String path, List<Layout> members) implements Layout {
        /** @throws NullPointerException if an argument is {@code null} */
        public Struct {
            Objects.requireNonNull(path, "path");
            members = List.copyOf(members);
        }

        @Override
        public int size() {
            return members.stream().mapToInt(Layout::size).sum();
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int ccsid) {
            List<WireValue> values = switch (value) {
                case WireValue.Struct s -> s.members();
                case WireValue.Blank b -> null;
                default -> throw LayoutException.wrongKind(path, "a struct");
            };
            if (values != null && values.size() != members.size()) {
                throw new LayoutException(path, "the struct does not have the members of the field");
            }
            int at = offset;
            for (int i = 0; i < members.size(); i++) {
                members.get(i).write(values == null ? new WireValue.Blank() : values.get(i), into, at, ccsid);
                at += members.get(i).size();
            }
        }

        @Override
        public WireValue read(byte[] from, int offset, int ccsid) {
            List<WireValue> values = new ArrayList<>(members.size());
            int at = offset;
            for (Layout member : members) {
                values.add(member.read(from, at, ccsid));
                at += member.size();
            }
            return new WireValue.Struct(values);
        }
    }

    /**
     * An array of a fixed number of elements, one after the other; it always takes the full declared size.
     *
     * @param path    the field
     * @param count   the number of elements
     * @param element the layout of one element
     */
    record Array(String path, int count, Layout element) implements Layout {
        /**
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code count} is negative
         */
        public Array {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(element, "element");
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }

        @Override
        public int size() {
            return count * element.size();
        }

        @Override
        public void write(WireValue value, byte[] into, int offset, int ccsid) {
            List<WireValue> values = switch (value) {
                case WireValue.Array a -> a.elements();
                case WireValue.Blank b -> null;
                default -> throw LayoutException.wrongKind(path, "a list");
            };
            if (values != null && values.size() != count) {
                throw new LayoutException(path, "the list does not have the number of elements of the field");
            }
            for (int i = 0; i < count; i++) {
                element.write(values == null ? new WireValue.Blank() : values.get(i), into, offset + i * element.size(), ccsid);
            }
        }

        @Override
        public WireValue read(byte[] from, int offset, int ccsid) {
            List<WireValue> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(element.read(from, offset + i * element.size(), ccsid));
            }
            return new WireValue.Array(values);
        }
    }
}
