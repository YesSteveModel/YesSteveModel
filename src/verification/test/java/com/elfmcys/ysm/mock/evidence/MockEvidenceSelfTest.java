package com.elfmcys.ysm.mock.evidence;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("model-management-mock")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MockEvidenceSelfTest {
    @Test
    @Order(1)
    void recordsPassingSelfTestWithoutCreatingAcceptanceEvidence() throws IOException {
        var root = configuredRoot();
        var run = open(root);
        var input = EvidenceJson
            .canonicalBytes(Map.of("actions", List.of("record", "complete"), "expected", Map.of("verdict", "Pass")));
        var identity =
            run.identity("i01-self-test-pass", List.of(), List.of("CO-016"), EvidenceRun.Radius.UNIT, false, input);
        var scenario = run.scenario(identity, input);
        scenario.append(new JsonlEvidenceSink.Observation(identity.scenarioId(), "record", "self-test", null,
            "operation-pass", "oracle-observation", Map.of("actual", "Pass", "expected", "Pass")));
        scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS,
            List.of("The append-only observation matched the fixed expected fact"), List.of(),
            Map.of("ERROR", 0L, "contractViolation", 0L),
            "Evidence foundation self-test; no product boundary was exercised"));

        assertTrue(Files.readString(root.resolve("index.jsonl")).contains("i01-self-test-pass/verdict.json"));
    }

    @Test
    @Order(2)
    void recordsIntentionalFailureBeforeJUnitReturnsNonZero() throws IOException {
        var root = configuredRoot();
        assumeTrue(Boolean.getBoolean("ysm.mock.intentionalFailure"));
        var run = open(root);
        var input = EvidenceJson
            .canonicalBytes(Map.of("actions", List.of("record", "fail"), "expected", Map.of("verdict", "Pass")));
        var identity = run.identity("i01-self-test-intentional-fail", List.of(), List.of("CO-016"),
            EvidenceRun.Radius.UNIT, false, input);
        var scenario = run.scenario(identity, input);
        scenario.append(new JsonlEvidenceSink.Observation(identity.scenarioId(), "fail", "self-test", null,
            "operation-fail", "oracle-observation", Map.of("actual", "Fail", "expected", "Pass")));
        scenario.retainFailureArtifact("intentional-failure.txt",
            "Intentional evidence-pipeline failure".getBytes(StandardCharsets.UTF_8));
        scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL,
            List.of("The intentional actual value differed from the fixed expected fact"),
            List.of("actual=Fail, expected=Pass"), Map.of("ERROR", 0L, "contractViolation", 0L),
            "Evidence foundation self-test; no product boundary was exercised"));

        fail("Intentional I-01 failure after evidence retention");
    }

    private static Path configuredRoot() {
        var configured = System.getProperty("ysm.mock.evidenceRoot");
        assumeTrue(configured != null && !configured.isBlank(), "Only the explicit mock task writes stable evidence");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static EvidenceRun open(Path root) throws IOException {
        var configured = Path.of(System.getProperty("ysm.mock.evidenceRoot")).toAbsolutePath().normalize();
        if (!root.equals(configured)) {
            throw new IllegalArgumentException("Evidence root does not match configuration");
        }
        return EvidenceRun.openConfigured();
    }
}
