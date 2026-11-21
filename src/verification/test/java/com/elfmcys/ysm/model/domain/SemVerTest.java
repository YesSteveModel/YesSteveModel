package com.elfmcys.ysm.model.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemVerTest {
    @Test
    void followsSemVerPrecedenceIncludingArbitrarilyWideNumbers() {
        var ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0",
            "999999999999999999999999999999999999999999.0.0", "1000000000000000000000000000000000000000000.0.0");
        for (var index = 1; index < ordered.size(); index++) {
            assertTrue(SemVer.parse(ordered.get(index - 1)).compareTo(SemVer.parse(ordered.get(index))) < 0);
        }
        assertTrue(SemVer.parse("1.0.0-999999999999999999999999999999")
            .compareTo(SemVer.parse("1.0.0-1000000000000000000000000000000")) < 0);
        assertTrue(SemVer.parse("1.0.0-999999999999999999999999999999").compareTo(SemVer.parse("1.0.0-a")) < 0);
    }

    @Test
    void buildChangesIdentityButDoesNotChangePrecedence() {
        var first = SemVer.parse("1.0.0+First.01");
        var second = SemVer.parse("1.0.0+Second");
        assertNotEquals(first, second);
        assertEquals(0, first.compareTo(second));
        assertNotEquals(ModelId.parse("owner/name@" + first), ModelId.parse("owner/name@" + second));
    }

    @Test
    void enforcesSyntaxAndAsciiLengthWithoutNumericOverflow() {
        for (var text : List.of("", "1", "1.0", "01.0.0", "1.01.0", "1.0.01", "1.0.0-01", "1.0.0-", "1.0.0+",
            "1.0.0-a..b", "1.0.0+名", " 1.0.0", "1.0.0\n", "1.0.0+" + "a".repeat(123))) {
            assertThrows(IllegalArgumentException.class, () -> SemVer.parse(text), text);
        }
        assertEquals(128, SemVer.parse("1.0.0+" + "a".repeat(122)).toString().length());
    }
}
