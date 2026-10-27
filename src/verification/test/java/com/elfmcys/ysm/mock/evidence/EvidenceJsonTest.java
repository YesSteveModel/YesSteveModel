package com.elfmcys.ysm.mock.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceJsonTest {
    @TempDir
    Path temporary;

    @Test
    void canonicalHashIgnoresMapInsertionOrder() {
        var first = new LinkedHashMap<String, Object>();
        first.put("z", List.of(3, 2, 1));
        first.put("a", Map.of("right", "二", "left", "一"));
        var second = new LinkedHashMap<String, Object>();
        second.put("a", Map.of("left", "一", "right", "二"));
        second.put("z", List.of(3, 2, 1));

        var firstBytes = EvidenceJson.canonicalBytes(first);
        var secondBytes = EvidenceJson.canonicalBytes(second);

        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(EvidenceJson.sha256(firstBytes), EvidenceJson.sha256(secondBytes));
    }

    @Test
    void runLocalTimestampAndDirectoryDoNotEnterScenarioIdentity() throws IOException {
        var stable = new EvidenceRun.Revisions(2, 2, 1, 1);
        var first = EvidenceRun.open(temporary.resolve("one"), metadata(stable, "time-one", "one"));
        var second = EvidenceRun.open(temporary.resolve("two"), metadata(stable, "time-two", "two"));
        var input = EvidenceJson.canonicalBytes(Map.of("action", "fixed"));

        assertEquals(
            first.identity("stable", List.of("FA-001"), List.of("CO-016"), EvidenceRun.Radius.UNIT, true, input),
            second.identity("stable", List.of("FA-001"), List.of("CO-016"), EvidenceRun.Radius.UNIT, true, input));
    }

    private static EvidenceRun.Metadata metadata(EvidenceRun.Revisions revisions, String startedAt,
        String outputDirectory) {
        return new EvidenceRun.Metadata(revisions, Map.of("java", "abc"), Map.of("mode", "test"), Map.of("os", "test"),
            List.of("gradlew", "test"), startedAt, outputDirectory);
    }
}
