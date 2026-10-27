package com.elfmcys.ysm.mock.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioEvidenceTest {
    @TempDir
    Path temporary;

    @Test
    void jsonlSinkAppendsAndRoundTripsUtf8() throws IOException {
        var path = temporary.resolve("events.client-a.jsonl");
        var first = new JsonlEvidenceSink(path, "client-a");
        first.append(observation("scenario", "one", "client-a", "模型已就绪"));
        new JsonlEvidenceSink(path, "client-a").append(observation("scenario", "two", "client-a", "页面关闭"));

        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("模型已就绪"));
        assertTrue(lines.get(1).contains("页面关闭"));
    }

    @Test
    void concurrentRoleWritersStayInSeparateFiles() throws Exception {
        var start = new CountDownLatch(1);
        var client = new JsonlEvidenceSink(temporary.resolve("events.client.jsonl"), "client");
        var server = new JsonlEvidenceSink(temporary.resolve("events.server.jsonl"), "server");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var clientWrite = executor.submit(() -> appendAfter(start, client, "client"));
            var serverWrite = executor.submit(() -> appendAfter(start, server, "server"));
            start.countDown();
            clientWrite.get();
            serverWrite.get();
        } finally {
            executor.shutdownNow();
        }

        var clientLines = Files.readAllLines(temporary.resolve("events.client.jsonl"));
        var serverLines = Files.readAllLines(temporary.resolve("events.server.jsonl"));
        assertEquals(20, clientLines.size());
        assertEquals(20, serverLines.size());
        assertTrue(clientLines.stream().allMatch(line -> line.contains("\"role\":\"client\"")));
        assertTrue(serverLines.stream().allMatch(line -> line.contains("\"role\":\"server\"")));
    }

    @Test
    void inventoryRejectsDuplicateArtifactPath() throws IOException {
        var artifact = Files.writeString(temporary.resolve("artifact.txt"), "retained");
        var inventory = new ArtifactInventory(temporary);
        inventory.register("first", artifact);

        assertThrows(IllegalArgumentException.class, () -> inventory.register("second", artifact));
    }

    @Test
    void failVerdictRetainsArtifactAndAddsReviewableIndex() throws IOException {
        var run = run(temporary);
        var input = EvidenceJson.canonicalBytes(Map.of("expected", "failure"));
        var identity =
            run.identity("failure-retention", List.of(), List.of("CO-016"), EvidenceRun.Radius.UNIT, false, input);
        var scenario = run.scenario(identity, input);
        var failure =
            scenario.retainFailureArtifact("oracle.txt", "独立 oracle mismatch".getBytes(StandardCharsets.UTF_8));
        scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL, List.of("actual != expected"),
            List.of("mismatch"), Map.of("ERROR", 1L), "Unit-only self-test evidence"));

        assertEquals("failure/oracle.txt", failure);
        assertTrue(Files.readString(temporary.resolve("failure-retention").resolve(failure)).contains("独立 oracle"));
        var index = Files.readString(temporary.resolve("index.jsonl"));
        assertTrue(index.contains("failure-retention/verdict.json"));
        assertTrue(index.contains("\"acceptanceEligible\":false"));
    }

    private static void appendAfter(CountDownLatch start, JsonlEvidenceSink sink, String role) {
        try {
            start.await();
            for (var index = 0; index < 20; index++) {
                sink.append(observation("scenario", "action-" + index, role, "value-" + index));
            }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static JsonlEvidenceSink.Observation observation(String scenario, String action, String role,
        String value) {
        return new JsonlEvidenceSink.Observation(scenario, action, role, null, null, "observation",
            Map.of("value", value));
    }

    private static EvidenceRun run(Path root) throws IOException {
        return EvidenceRun.open(root,
            new EvidenceRun.Metadata(new EvidenceRun.Revisions(2, 2, 1, 1), Map.of("java", "abc"),
                Map.of("mode", "unit"), Map.of("os", "test"), List.of("gradlew", "test"), "now", root.toString()));
    }
}
