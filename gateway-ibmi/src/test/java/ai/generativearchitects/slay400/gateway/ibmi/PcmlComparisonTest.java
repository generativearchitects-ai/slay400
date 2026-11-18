package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The comparison of the setup tool, without a system. */
class PcmlComparisonTest {

    static final String FIXTURE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pcml version="6.0">
              <struct name="S"><data name="A" type="char" length="2" usage="inherit"/></struct>
              <program name="P" path="/QSYS.LIB/OWNERLIB.LIB/P.PGM">
                <data name="X" type="char" length="10" usage="input"/>
                <data name="Y" type="struct" struct="S" usage="inputoutput"/>
              </program>
            </pcml>
            """;

    @Test
    void commentsWhiteSpaceAndTheOrderOfAttributesDoNotCount() {
        String same = """
                <?xml version="1.0" encoding="UTF-8"?>
                <pcml version="6.0">
                  <!-- RPG program: P -->
                  <struct name="S">
                      <data usage="inherit" length="2" type="char" name="A"/>
                  </struct>
                  <program path="/QSYS.LIB/OWNERLIB.LIB/P.PGM" name="P">
                    <data name="X" type="char" length="10" usage="input"/>
                    <data name="Y" type="struct" struct="S" usage="inputoutput"/>
                  </program>
                </pcml>
                """;
        assertThat(PcmlComparison.differences(FIXTURE, same)).isEmpty();
    }

    @Test
    void everyKindOfDifferenceIsOneLine() {
        String other = """
                <pcml version="4.0">
                  <program name="P" path="/QSYS.LIB/OWNERLIB.LIB/P.PGM">
                    <data name="Y" type="struct" struct="Y" usage="inputoutput"/>
                    <data name="X" type="char" length="10" usage="inputoutput" init="A"/>
                  </program>
                  <struct name="Y"><data name="A" type="char" length="2" usage="inherit"/></struct>
                </pcml>
                """;
        assertThat(PcmlComparison.differences(FIXTURE, other)).containsExactly(
                "pcml: attribute version is \"4.0\" (the fixture has \"6.0\")",
                "pcml: the compiler wrote no struct S",
                "pcml > program P > data X: attribute usage is \"inputoutput\" (the fixture has \"input\")",
                "pcml > program P > data X: the compiler wrote attribute init=\"A\", which the fixture does not have",
                "pcml > program P > data Y: attribute struct is \"Y\" (the fixture has \"S\")",
                "pcml > program P: the elements are in another order: data Y, data X (the fixture has data X, data Y)",
                "pcml: the compiler wrote struct Y, which the fixture does not have");
        assertThat(PcmlComparison.differences(FIXTURE, FIXTURE.replace(" length=\"10\"", ""))).containsExactly(
                "pcml > program P > data X: the compiler wrote no attribute length (the fixture has \"10\")");
    }

    @Test
    void documentsThatAreNotXmlOrHaveADoctypeAreReported() {
        assertThat(PcmlComparison.differences(FIXTURE, "<pcml>")).containsExactly("the compiler's PCML is not valid XML (SAXParseException)");
        assertThat(PcmlComparison.differences("x", FIXTURE)).containsExactly("the fixture is not valid XML (SAXParseException)");
        assertThat(PcmlComparison.differences(FIXTURE, "<!DOCTYPE pcml [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><pcml>&e;</pcml>"))
                .containsExactly("the compiler's PCML is not valid XML (SAXParseException)");
    }

    @Test
    void elementsWithTheSameNameAreKeptApart() {
        String twice = FIXTURE.replace("<data name=\"X\" type=\"char\" length=\"10\" usage=\"input\"/>",
                "<data name=\"X\" type=\"char\" length=\"10\" usage=\"input\"/><data name=\"X\" type=\"char\" length=\"10\" usage=\"input\"/>");
        assertThat(PcmlComparison.differences(FIXTURE, twice)).containsExactly("pcml > program P: the compiler wrote data X #2, which the fixture does not have");
    }
}
