package com.elfmcys.ysm.mock.supervisor;

import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.MockExact100Input;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Owns only ordinary-JVM process lifecycle, immutable actions, and evidence retention. */
public final class MockSupervisor {
    private static final String SCENARIO_ID = "MMR-CLASSPATH-SMOKE-001";
    private static final String SYSTEM_SCENARIO_ID = "MMR-CLASSPATH-SYSTEM-001";
    private static final String EXACT_100_SCENARIO_ID = "MMR-CLASSPATH-100-001";
    private static final String CLIENT_MAIN = "com.elfmcys.ysm.network.forge.MockClientEndpoint";
    private static final String SERVER_MAIN = "com.elfmcys.ysm.network.forge.MockServerEndpoint";
    private static final Duration READY_GUARD = Duration.ofSeconds(20);
    private static final Duration EXIT_GUARD = Duration.ofSeconds(30);
    private static final List<String> ENDPOINT_JVM_ARGUMENTS =
        List.of("-Dlog4j2.configurationFile=classpath:log4j2-mock-smoke.xml");

    private MockSupervisor() {}

    public static void main(String[] args) throws Throwable {
        if (args.length != 8 && args.length != 10) {
            throw new IllegalArgumentException("Expected evidence root, Java revision, implementation revision, "
                + "Java executable, two classpath entry files, two endpoint output roots, and optional native library/mode");
        }
        var evidenceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        var javaRevision = args[1];
        var implementationRevision = Integer.parseInt(args[2]);
        var javaExecutable = Path.of(args[3]).toAbsolutePath().normalize();
        var clientEntries = readEntries(Path.of(args[4]));
        var serverEntries = readEntries(Path.of(args[5]));
        var clientOutput = Path.of(args[6]).toAbsolutePath().normalize();
        var serverOutput = Path.of(args[7]).toAbsolutePath().normalize();
        require(Files.isRegularFile(evidenceRoot.resolve(".ysm-model-management-evidence")),
            "Evidence root does not contain the ownership marker");
        require(javaRevision.matches("[0-9a-f]{40}"), "Java authority revision must be a full commit");
        if (args.length == 10) {
            require("full".equals(args[9]) || "exact-100".equals(args[9]), "Unknown classpath scenario mode");
            runSystem(evidenceRoot, javaRevision, implementationRevision, javaExecutable, clientEntries, serverEntries,
                clientOutput, serverOutput, Path.of(args[8]).toAbsolutePath().normalize(), args[9]);
            return;
        }

        var port = freePort();
        var clientPaths = EndpointPaths.create(evidenceRoot, "client");
        var serverPaths = EndpointPaths.create(evidenceRoot, "server");
        var clientCommand = endpointCommand(javaExecutable, clientPaths.launchJar(), CLIENT_MAIN, port, clientPaths);
        var serverCommand = endpointCommand(javaExecutable, serverPaths.launchJar(), SERVER_MAIN, port, serverPaths);
        var clientManifest = classpathManifest("client", "mockHostClientALegacyClasspath", clientEntries, clientCommand,
            clientOutput, serverOutput);
        var serverManifest = classpathManifest("dedicated-server", "mockHostServerLegacyClasspath", serverEntries,
            serverCommand, serverOutput, clientOutput);
        require(!clientManifest.aggregateSha256().equals(serverManifest.aggregateSha256()),
            "Client and server physical classpath manifests unexpectedly match");
        require(clientManifest.ownEndpointOutputPresent() && serverManifest.ownEndpointOutputPresent(),
            "A physical classpath omitted its own endpoint output");
        require(clientManifest.oppositeEndpointOutputAbsent() && serverManifest.oppositeEndpointOutputAbsent(),
            "A physical classpath included the opposite endpoint output");

        writeNew(evidenceRoot.resolve("classpath.client.json"), clientManifest);
        writeNew(evidenceRoot.resolve("classpath.server.json"), serverManifest);

        var metadata = new EvidenceRun.Metadata(new EvidenceRun.Revisions(3, 3, 2, implementationRevision),
            Map.of("java", javaRevision),
            Map.of("adapterInventory", "socket-byte-carrier,empty-publication-fixture", "bootstrap",
                "ordinary-java-main-no-forge-launcher", "childProcessPolicy",
                "only-attributed-os-console-hosts-while-running;none-after-cleanup", "clientClasspathSha256",
                clientManifest.aggregateSha256(), "clientWorkingDirectory", clientPaths.workingDirectory().toString(),
                "physicalSides", "client,dedicated-server", "serverClasspathSha256", serverManifest.aggregateSha256(),
                "serverWorkingDirectory", serverPaths.workingDirectory().toString(), "task",
                "modelManagementMockSmoke"),
            Map.of("arch", System.getProperty("os.arch"), "java", System.getProperty("java.version"), "os",
                System.getProperty("os.name")),
            List.of("./gradlew modelManagementMockSmoke", String.join(" ", serverCommand),
                String.join(" ", clientCommand)),
            Instant.now().toString(), evidenceRoot.toString());
        var run = EvidenceRun.open(evidenceRoot, metadata);
        recordEvidenceSelfTest(run);

        var input = EvidenceJson.canonicalBytes(Map.of("actions",
            List.of("load-side-closures", "server-ready", "client-ready", "hello", "session-response", "publication-0",
                "publication-1", "publication-2", "publication-3", "select-request", "select-response", "owner-close",
                "clean-exit", "remove-client-quickbuf", "remove-server-quickbuf"),
            "expected", Map.of("clientExit", 0, "publicationEntries", 0, "selection", "intrinsic-default", "serverExit",
                0, "typedFramePairs", 8),
            "physicalSides", List.of("client", "dedicated-server")));
        var identity = run.identity(SCENARIO_ID, List.of("FA-005", "FA-006"),
            List.of("CO-012", "CO-014", "CO-016", "CO-018"), EvidenceRun.Radius.CLASSPATH_SYSTEM, false, input);
        var scenario = run.scenario(identity, input);
        try {
            retainRootArtifacts(scenario, evidenceRoot);

            var positive = runPositive(javaExecutable, clientEntries, serverEntries, clientPaths, serverPaths, port);
            appendEndpointEvents(scenario, positive.clientEvents());
            appendEndpointEvents(scenario, positive.serverEvents());
            retainPositiveArtifacts(scenario, positive, clientPaths, serverPaths);
            verifyPositive(positive, clientManifest, serverManifest);

            var clientNegative = runMissingDependency(javaExecutable, "client", CLIENT_MAIN, clientEntries,
                clientOutput, serverOutput, evidenceRoot.resolve("negative/client"));
            var serverNegative = runMissingDependency(javaExecutable, "dedicated-server", SERVER_MAIN, serverEntries,
                serverOutput, clientOutput, evidenceRoot.resolve("negative/server"));
            verifyNegative(clientNegative);
            verifyNegative(serverNegative);
            scenario.retainArtifact("negative/client/result.json", EvidenceJson.canonicalBytes(clientNegative));
            scenario.retainArtifact("negative/client/stdout.log", Files.readAllBytes(Path.of(clientNegative.stdout())));
            scenario.retainArtifact("negative/server/result.json", EvidenceJson.canonicalBytes(serverNegative));
            scenario.retainArtifact("negative/server/stdout.log", Files.readAllBytes(Path.of(serverNegative.stdout())));
            scenario.append(observation("dependency-negative", "supervisor", "dependency-discrimination",
                Map.of("clientExit", Integer.toString(clientNegative.exitCode()), "clientRemoved",
                    clientNegative.removedDependency(), "clientReady", Boolean.toString(clientNegative.readyObserved()),
                    "serverExit", Integer.toString(serverNegative.exitCode()), "serverRemoved",
                    serverNegative.removedDependency(), "serverReady",
                    Boolean.toString(serverNegative.readyObserved()))));

            scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, List.of(
                "Client and dedicated-server endpoint outputs resolved and executed on distinct distribution-attributed physical classpaths",
                "Both ordinary JVMs loaded the production protocol registry from empty working directories without a Forge or Minecraft launcher",
                "Eight typed frame pairs crossed the byte-only carrier through ProtocolMessages, ProtocolBuffer, and FrameCodec",
                "Production client/server model sessions completed hello, empty atomic publication, intrinsic-default request/response, owner close, and exit 0",
                "Removing quickbuf from either physical closure failed before ready while the opposite endpoint output could not supply it"),
                List.of(),
                Map.of("ERROR", 0L, "contractViolation", 0L, "forcedCleanup", 0L, "unexpectedChildProcess", 0L),
                "Classpath foundation only: the empty publication and intrinsic-default request do not prove full FA-005, exact-100 FA-006, actual Forge channel/event/thread behavior, non-empty asset transfer/activation, or physical resource reclamation"));
        } catch (Throwable failure) {
            var trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            scenario.retainFailureArtifact("classpath-smoke-failure.txt",
                trace.toString().getBytes(StandardCharsets.UTF_8));
            scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL,
                List.of("The classpath foundation stopped at a machine assertion or process boundary"),
                List.of(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage())),
                Map.of("ERROR", 1L, "contractViolation", 1L),
                "Failure is scoped to the recorded ordinary-JVM classpath smoke"));
            throw failure;
        }
    }

    private static void runSystem(Path evidenceRoot, String javaRevision, int implementationRevision,
        Path javaExecutable, List<Path> clientEntries, List<Path> serverEntries, Path clientOutput, Path serverOutput,
        Path nativeLibrary, String mode) throws Throwable {
        var scale = mode.equals("exact-100");
        var scenarioId = scale ? EXACT_100_SCENARIO_ID : SYSTEM_SCENARIO_ID;
        var taskName = scale ? "modelManagementMockExact100" : "modelManagementMockSystem";
        require(Files.isRegularFile(nativeLibrary), "Native runtime library is missing: " + nativeLibrary);
        var nativeLibrarySha256 = EvidenceJson.sha256(Files.readAllBytes(nativeLibrary));
        var firstRoot = evidenceRoot.resolve("replay-1");
        var firstClient = EndpointPaths.create(firstRoot, "client");
        var firstServer = EndpointPaths.create(firstRoot, "server");
        var firstPort = freePort();
        var firstInput = firstRoot.resolve("input.json");
        var clientCommand = systemEndpointCommand(javaExecutable, firstClient.launchJar(), CLIENT_MAIN, firstPort,
            firstClient, firstInput, nativeLibrary, scenarioId, mode);
        var serverCommand = systemEndpointCommand(javaExecutable, firstServer.launchJar(), SERVER_MAIN, firstPort,
            firstServer, firstInput, nativeLibrary, scenarioId, mode);
        var clientManifest = classpathManifest("client", "mockHostClientALegacyClasspath", clientEntries, clientCommand,
            clientOutput, serverOutput);
        var serverManifest = classpathManifest("dedicated-server", "mockHostServerLegacyClasspath", serverEntries,
            serverCommand, serverOutput, clientOutput);
        require(!clientManifest.aggregateSha256().equals(serverManifest.aggregateSha256()),
            "Client and server physical classpath manifests unexpectedly match");
        require(clientManifest.ownEndpointOutputPresent() && serverManifest.ownEndpointOutputPresent(),
            "A physical classpath omitted its own endpoint output");
        require(clientManifest.oppositeEndpointOutputAbsent() && serverManifest.oppositeEndpointOutputAbsent(),
            "A physical classpath included the opposite endpoint output");
        writeNew(evidenceRoot.resolve("classpath.client.json"), clientManifest);
        writeNew(evidenceRoot.resolve("classpath.server.json"), serverManifest);

        var metadata = new EvidenceRun.Metadata(new EvidenceRun.Revisions(3, 3, 2, implementationRevision),
            Map.of("java", javaRevision),
            Map.ofEntries(Map.entry("adapterInventory", "socket-byte-carrier,fixture-source,logical-host-callback"),
                Map.entry("bootstrap", "ordinary-java-main-no-forge-launcher"),
                Map.entry("childProcessPolicy", "only-attributed-os-console-hosts-while-running;none-after-cleanup"),
                Map.entry("clientClasspathSha256", clientManifest.aggregateSha256()),
                Map.entry("physicalSides", "client,dedicated-server"), Map.entry("replayCount", "2"),
                Map.entry("nativeLibrarySha256", nativeLibrarySha256),
                Map.entry("nativeLibraryPath", normalize(nativeLibrary)),
                Map.entry("serverClasspathSha256", serverManifest.aggregateSha256()), Map.entry("scenarioMode", mode),
                Map.entry("task", taskName)),
            Map.of("arch", System.getProperty("os.arch"), "java", System.getProperty("java.version"), "os",
                System.getProperty("os.name")),
            List.of("./gradlew " + taskName, String.join(" ", serverCommand), String.join(" ", clientCommand)),
            Instant.now().toString(), evidenceRoot.toString());
        var run = EvidenceRun.open(evidenceRoot, metadata);
        final SystemRun first;
        final SystemRun second;
        try {
            first = runSystemPositive(javaExecutable, clientEntries, serverEntries, firstRoot, firstPort, nativeLibrary,
                scenarioId, mode);
            second = runSystemPositive(javaExecutable, clientEntries, serverEntries, evidenceRoot.resolve("replay-2"),
                freePort(), nativeLibrary, scenarioId, mode);
        } catch (Throwable failure) {
            writeSystemLaunchFailure(evidenceRoot, failure);
            throw failure;
        }
        require(EvidenceJson.sha256(first.input()).equals(EvidenceJson.sha256(second.input())),
            "Required replays did not use the same immutable input hash");
        var identity = run.identity(scenarioId,
            scale ? List.of("FA-006", "FA-007") : List.of("FA-002", "FA-003", "FA-004", "FA-005", "FA-007"),
            scale ? List.of("CO-004", "CO-005", "CO-007", "CO-010", "CO-012", "CO-013", "CO-016", "CO-018")
                : List.of("CO-003", "CO-004", "CO-005", "CO-006", "CO-007", "CO-008", "CO-009", "CO-010", "CO-011",
                    "CO-012", "CO-013", "CO-014", "CO-016", "CO-018"),
            EvidenceRun.Radius.CLASSPATH_SYSTEM, true, first.input());
        var scenario = run.scenario(identity, first.input());
        try {
            retainRootArtifacts(scenario, evidenceRoot);
            if (scale) {
                verifyExact100(first, clientManifest, serverManifest);
                verifyExact100(second, clientManifest, serverManifest);
            } else {
                verifySystem(first, clientManifest, serverManifest);
                verifySystem(second, clientManifest, serverManifest);
            }
            var firstLogical = logicalResult(first);
            var secondLogical = logicalResult(second);
            require(firstLogical.equals(secondLogical),
                "Required replays produced different logical results or owner counts");
            appendSystemEvents(scenario, scenarioId, "replay-1", first.clientEvents());
            appendSystemEvents(scenario, scenarioId, "replay-1", first.serverEvents());
            appendSystemEvents(scenario, scenarioId, "replay-2", second.clientEvents());
            appendSystemEvents(scenario, scenarioId, "replay-2", second.serverEvents());
            retainSystemArtifacts(scenario, "replay-1", first);
            retainSystemArtifacts(scenario, "replay-2", second);
            scenario.append(new JsonlEvidenceSink.Observation(scenarioId, "replay-equivalence", "supervisor", null,
                scenarioId, "deterministic-replay", Map.of("inputSha256", EvidenceJson.sha256(first.input()),
                    "logicalResultSha256", firstLogical, "replayCount", "2")));
            if (scale) {
                scenario.append(new JsonlEvidenceSink.Observation(scenarioId, "optional-characterization", "supervisor",
                    null, "session-cardinality", "not-run",
                    Map.of("200-session", "Not run (future Bukkit characterization)", "300-session",
                        "Not run (extreme characterization)")));
            }
            var claims = scale ? List.of(
                "Two exact-100 runs executed one serialized per-connection action and expectation plan on the side-correct ordinary-JVM runtime and produced identical logical results",
                "All 100 logical players traversed production server session/admission/dispatch/transfer and client receive/publication/server-source/activation/terminal paths",
                "Transport pressure, production source failure, cancellation and re-request, replacement, leave, unauthorized request, grant, and intrinsic-invalid request remained isolated",
                "Every accepted transfer and explicit two-sided owner reached exactly one terminal while queue counts remained within production hardLimit=4",
                "Wall time, dispatch lag, MSPT-equivalent, and heap trend were retained as non-gating soft metrics; 200/300 remained Not run characterization")
                : List.of(
                    "Two side-correct ordinary JVM endpoints repeated the same immutable action stream with identical logical results and exact owner counts",
                    "Production session, publication receiver/producer, typed codec, ClientAssetTransfer, ServerAssetTransfers, ResourceDispatchWorker, catalog manager, exact cache/source selection, and per-entry activation were traversed",
                    "Initial full, compatible authorization deltas, baseline drift, invalid publication, exact local/cache/server sources, server/cache failure, disconnect/reconnect, late old completion, and both-side close barriers matched the independent expected facts",
                    "Unauthorized work was rejected before dispatch while accepted work completed after authorization revocation at its admission boundary",
                    "Every typed message and resource fragment had a byte-identical send/receive correlation on its exact physical connection");
            scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, claims, List.of(),
                Map.of("ERROR", 0L, "contractViolation", 0L, "forcedCleanup", 0L, "lateCurrentEffect", 0L,
                    "orphanAcceptedWork", 0L),
                scale
                    ? "FA-006 exact-100 classpath-system radius; 100 logical owners are multiplexed by one client endpoint JVM and one server endpoint JVM, not 100 Forge clients. Timing and heap observations are soft only."
                    : "FA-005 classpath-system radius only: actual Forge channel/event, Minecraft owner thread, render/native adoption, gameplay connection preservation, and physical reclamation remain excluded"));
        } catch (Throwable failure) {
            var trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            scenario.retainFailureArtifact(scale ? "classpath-exact-100-failure.txt" : "classpath-system-failure.txt",
                trace.toString().getBytes(StandardCharsets.UTF_8));
            scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL, List.of(),
                List.of(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage())),
                Map.of("ERROR", 1L, "contractViolation", 1L),
                scale ? "Failure is scoped to the recorded FA-006 exact-100 ordinary-JVM scale mode"
                    : "Failure is scoped to the recorded FA-005 ordinary-JVM system scenario"));
            throw failure;
        }
    }

    private static SystemRun runSystemPositive(Path javaExecutable, List<Path> clientEntries, List<Path> serverEntries,
        Path replayRoot, int port, Path nativeLibrary, String scenarioId, String mode) throws Exception {
        var client = EndpointPaths.create(replayRoot, "client");
        var server = EndpointPaths.create(replayRoot, "server");
        Files.createDirectories(client.workingDirectory());
        Files.createDirectories(server.workingDirectory());
        require(directoryEmpty(client.workingDirectory()) && directoryEmpty(server.workingDirectory()),
            "Endpoint working directories were not empty before launch");
        writeClasspathJar(client.launchJar(), clientEntries);
        writeClasspathJar(server.launchJar(), serverEntries);
        var input = replayRoot.resolve("input.json");
        Process serverProcess = null;
        Process clientProcess = null;
        ProcessSupervisor.CleanupResult serverCleanup = null;
        ProcessSupervisor.CleanupResult clientCleanup = null;
        var serverExit = Integer.MIN_VALUE;
        var clientExit = Integer.MIN_VALUE;
        var serverChildren = List.<ChildProcess>of();
        var clientChildren = List.<ChildProcess>of();
        byte[] inputBytes = null;
        try {
            serverProcess = ProcessSupervisor.launchJava(javaExecutable, ENDPOINT_JVM_ARGUMENTS, server.launchJar(),
                SERVER_MAIN, systemEndpointArguments(port, server, input, nativeLibrary, scenarioId, mode),
                server.workingDirectory(), Map.of(), server.stdout());
            ProcessSupervisor.awaitFile(server.ready(), serverProcess, READY_GUARD);
            serverChildren = childProcesses(serverProcess);
            clientProcess = ProcessSupervisor.launchJava(javaExecutable, ENDPOINT_JVM_ARGUMENTS, client.launchJar(),
                CLIENT_MAIN, systemEndpointArguments(port, client, input, nativeLibrary, scenarioId, mode),
                client.workingDirectory(), Map.of(), client.stdout());
            ProcessSupervisor.awaitFile(client.ready(), clientProcess, READY_GUARD);
            clientChildren = childProcesses(clientProcess);
            var clientFixture = readFixture(client.ready());
            var serverFixture = readFixture(server.ready());
            require(
                EvidenceJson.sha256(EvidenceJson.canonicalBytes(clientFixture))
                    .equals(EvidenceJson.sha256(EvidenceJson.canonicalBytes(serverFixture))),
                "Client and server independently generated different fixture identities");
            inputBytes = mode.equals("exact-100") ? exact100Input(clientFixture) : systemInput(clientFixture);
            Files.write(input, inputBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var exitGuard = mode.equals("exact-100") ? Duration.ofMinutes(3) : EXIT_GUARD;
            clientExit = ProcessSupervisor.awaitExit(clientProcess, exitGuard);
            serverExit = ProcessSupervisor.awaitExit(serverProcess, exitGuard);
        } finally {
            clientCleanup = ProcessSupervisor.terminateExact(clientProcess, Duration.ofSeconds(2));
            serverCleanup = ProcessSupervisor.terminateExact(serverProcess, Duration.ofSeconds(2));
        }
        require(inputBytes != null, "System action input was not generated before execution");
        return new SystemRun(clientExit, serverExit, clientCleanup, serverCleanup, clientChildren, serverChildren,
            directoryEmpty(client.workingDirectory()), directoryEmpty(server.workingDirectory()),
            readObservations(client.events()), readObservations(server.events()), client, server, inputBytes);
    }

    private static JsonObject readFixture(Path ready) throws IOException {
        var root = JsonParser.parseString(Files.readString(ready, StandardCharsets.UTF_8)).getAsJsonObject();
        require(root.get("workingDirectoryEmpty").getAsBoolean(), "Endpoint did not report an empty working directory");
        return root.getAsJsonObject("fixture");
    }

    private static byte[] systemInput(JsonObject fixture) {
        var input = new JsonObject();
        input.add("fixture", fixture.deepCopy());
        var actions = new JsonArray();
        List.of("initial-full", "authorization-reject", "authorization-grant", "accepted-before-revoke",
            "authorization-revoke", "server-source-failure", "baseline-incompatible-delta", "replacement-old-pending",
            "physical-disconnect", "replacement-new", "late-old-completion", "cache-access-failure",
            "domain-invalid-full", "client-owner-close", "server-owner-close").forEach(actions::add);
        input.add("actions", actions);
        input.add("expectedClient", expectedClientFacts());
        input.add("expectedServer", expectedServerFacts());
        input.addProperty("scenario", SYSTEM_SCENARIO_ID);
        input.addProperty("topology", "client,dedicated-server");
        return EvidenceJson.canonicalBytes(input);
    }

    private static byte[] exact100Input(JsonObject fixture) {
        return EvidenceJson.canonicalBytes(Map.of("fixture", fixture, "scenario", EXACT_100_SCENARIO_ID,
            MockExact100Input.KEY, MockExact100Input.create(), "topology", "client,dedicated-server"));
    }

    private static JsonObject expectedClientFacts() {
        var facts = new JsonObject();
        expectedClient().forEach(facts::addProperty);
        return facts;
    }

    private static JsonObject expectedServerFacts() {
        var facts = new JsonObject();
        expectedServer().forEach(facts::addProperty);
        return facts;
    }

    private static Map<String, String> expectedClient() {
        return Map.ofEntries(Map.entry("acceptedAfterRevoke", "completed"),
            Map.entry("baselineIncompatibleDelta", "retained-authority"),
            Map.entry("clientCacheFailure", "transient-entry-local"), Map.entry("clientSessionCloses", "2"),
            Map.entry("clientSessionCreates", "2"), Map.entry("connectionAStateAfterFailure", "INTRINSIC_DEFAULT_ONLY"),
            Map.entry("currentConnection", "connection-B-retired"),
            Map.entry("domainInvalidPublication", "model-session-only"), Map.entry("errorCount", "0"),
            Map.entry("initialPublicationEntries", "5"), Map.entry("lateOldCacheEffect", "none"),
            Map.entry("oldSourceAtDisconnect", "accepted-pending"), Map.entry("replacementReady", "exact-current"),
            Map.entry("serverSourceFailure", "transient-entry-local"), Map.entry("sourceActiveExact", "active-exact"),
            Map.entry("sourceCacheExact", "remote-cache-exact"), Map.entry("sourceLocalExact", "local-exact"),
            Map.entry("sourceSameModel", "local-same-model-representation"),
            Map.entry("sourceServerExact", "server-exact"),
            Map.entry("unauthorizedAdmission", "rejected-before-dispatch"),
            Map.entry("unaffectedSentinel", "intrinsic-default-visible"));
    }

    private static Map<String, String> expectedServer() {
        return Map.ofEntries(Map.entry("acceptedTransferAuthorization", "admission-snapshot"),
            Map.entry("connectionATransferTerminals", "5"), Map.entry("connectionBTransferTerminals", "1"),
            Map.entry("currentConnection", "connection-B-retired"), Map.entry("dispatchQueuesAfterClose", "0"),
            Map.entry("errorCount", "0"), Map.entry("lateOldCurrentEffects", "0"),
            Map.entry("serverSessionCloses", "2"), Map.entry("serverSessionCreates", "2"),
            Map.entry("unauthorizedDispatchQueue", "0"));
    }

    private static void verifySystem(SystemRun run, MockManifest client, MockManifest server) throws IOException {
        require(run.clientExit() == 0 && run.serverExit() == 0,
            "System endpoint exit was not zero: client=" + run.clientExit() + " server=" + run.serverExit());
        require(run.clientCleanup().clean() && run.serverCleanup().clean() && !run.clientCleanup().forced()
            && !run.serverCleanup().forced(), "System endpoint cleanup was forced or incomplete");
        require(
            run.clientChildren().stream().allMatch(ChildProcess::osInfrastructure)
                && run.serverChildren().stream().allMatch(ChildProcess::osInfrastructure),
            "A system endpoint launched an unattributed child process");
        require(run.clientWorkingDirectoryEmpty() && run.serverWorkingDirectoryEmpty(),
            "A system endpoint wrote into its empty working directory");
        require(client.aggregateSha256() != null && server.aggregateSha256() != null,
            "Classpath aggregate hashes were not recorded");
        require(
            run.clientEvents().stream().noneMatch(event -> event.eventKind().equals("endpoint-failure"))
                && run.serverEvents().stream().noneMatch(event -> event.eventKind().equals("endpoint-failure")),
            "An endpoint reported a system failure");
        verifyFacts(systemResult(run.clientEvents()), expectedClient(), "client");
        verifyFacts(systemResult(run.serverEvents()), expectedServer(), "server");
        verifyTypedPairs(run.clientEvents(), run.serverEvents());
        require(
            run.clientEvents().stream().filter(event -> event.eventKind().equals("owner-close")).count() == 2
                && run.serverEvents().stream().filter(event -> event.eventKind().equals("owner-close")).count() == 2,
            "Two-sided exact session owners did not each close once");
    }

    private static void verifyExact100(SystemRun run, MockManifest client, MockManifest server) throws IOException {
        require(run.clientExit() == 0 && run.serverExit() == 0,
            "Exact-100 endpoint exit was not zero: client=" + run.clientExit() + " server=" + run.serverExit());
        require(run.clientCleanup().clean() && run.serverCleanup().clean() && !run.clientCleanup().forced()
            && !run.serverCleanup().forced(), "Exact-100 endpoint cleanup was forced or incomplete");
        require(
            run.clientChildren().stream().allMatch(ChildProcess::osInfrastructure)
                && run.serverChildren().stream().allMatch(ChildProcess::osInfrastructure),
            "An exact-100 endpoint launched an unattributed child process");
        require(run.clientWorkingDirectoryEmpty() && run.serverWorkingDirectoryEmpty(),
            "An exact-100 endpoint wrote into its empty working directory");
        require(
            client.aggregateSha256() != null && server.aggregateSha256() != null
                && !client.aggregateSha256().equals(server.aggregateSha256()),
            "Exact-100 did not retain distinct physical classpath identities");
        require(
            run.clientEvents().stream().noneMatch(event -> event.eventKind().equals("endpoint-failure"))
                && run.serverEvents().stream().noneMatch(event -> event.eventKind().equals("endpoint-failure")),
            "An endpoint reported an exact-100 failure");
        verifyTypedPairs(run.clientEvents(), run.serverEvents());
        var root = JsonParser.parseString(new String(run.input(), StandardCharsets.UTF_8)).getAsJsonObject();
        var plan = MockExact100Input.parse(root);
        var connectionCount = plan.players().stream().mapToLong(player -> player.connections().size()).sum();
        require(run.clientEvents().stream().filter(event -> event.eventKind().equals("owner-close"))
            .count() == connectionCount
            && run.serverEvents().stream().filter(event -> event.eventKind().equals("owner-close"))
                .count() == connectionCount,
            "Exact-100 did not close all 101 two-sided connection owners once");
        MockExact100Verifier.verify(root, exact100Observations(run.clientEvents()),
            exact100Observations(run.serverEvents()));
        require(
            run.clientEvents().stream().filter(event -> event.eventKind().equals("soft-metrics")).count() == 1
                && run.serverEvents().stream().filter(event -> event.eventKind().equals("soft-metrics")).count() == 1,
            "Exact-100 soft metrics were not recorded on both sides");

    }

    private static List<MockExact100Verifier.Observation> exact100Observations(List<Observed> events) {
        return events.stream().map(event -> new MockExact100Verifier.Observation(event.connectionId(), event.actionId(),
            event.eventKind(), event.payload())).toList();
    }

    private static Map<String, String> systemResult(List<Observed> events) throws IOException {
        var matches = events.stream().filter(event -> event.eventKind().equals("system-result")).toList();
        require(matches.size() == 1, "Expected one endpoint system result");
        return matches.get(0).payload();
    }

    private static void verifyFacts(Map<String, String> actual, Map<String, String> expected, String role)
        throws IOException {
        for (var entry : expected.entrySet()) {
            require(entry.getValue().equals(actual.get(entry.getKey())),
                role + " expected fact mismatch for " + entry.getKey());
        }
    }

    private static void verifyTypedPairs(List<Observed> client, List<Observed> server) throws IOException {
        var frames = new LinkedHashMap<String, List<Observed>>();
        for (var event : client) {
            if (event.eventKind().startsWith("typed-frame-")) {
                frames.computeIfAbsent(frameKey(event), ignored -> new ArrayList<>()).add(event);
            }
        }
        for (var event : server) {
            if (event.eventKind().startsWith("typed-frame-")) {
                frames.computeIfAbsent(frameKey(event), ignored -> new ArrayList<>()).add(event);
            }
        }
        require(frames.size() >= 20, "Full system traversed too few distinct typed frames");
        for (var entry : frames.entrySet()) {
            var pair = entry.getValue();
            require(pair.size() == 2 && !pair.get(0).eventKind().equals(pair.get(1).eventKind()),
                "Typed frame did not have one exact send/receive pair: " + entry.getKey());
        }
    }

    private static String frameKey(Observed event) {
        return event.connectionId() + "\0" + event.actionId() + "\0" + event.payload().get("sha256");
    }

    private static String logicalResult(SystemRun run) throws IOException {
        return EvidenceJson.sha256(EvidenceJson.canonicalBytes(
            Map.of("client", systemResult(run.clientEvents()), "server", systemResult(run.serverEvents()))));
    }

    private static void appendSystemEvents(ScenarioEvidence scenario, String scenarioId, String replay,
        List<Observed> events) throws IOException {
        for (var event : events) {
            var payload = new LinkedHashMap<>(event.payload());
            payload.put("replay", replay);
            scenario.append(new JsonlEvidenceSink.Observation(scenarioId, event.actionId(), event.role(),
                event.connectionId(), event.operationId(), event.eventKind(), Map.copyOf(payload)));
        }
    }

    private static void retainSystemArtifacts(ScenarioEvidence scenario, String replay, SystemRun run)
        throws IOException {
        scenario.retainArtifact(replay + "/input.json", run.input());
        for (var paths : List.of(run.clientPaths(), run.serverPaths())) {
            var role = paths == run.clientPaths() ? "client" : "server";
            for (var path : List.of(paths.ready(), paths.exit(), paths.events(), paths.stdout())) {
                scenario.retainArtifact(replay + "/processes/" + role + "/" + path.getFileName(),
                    Files.readAllBytes(path));
            }
        }
        scenario.retainArtifact(replay + "/processes/cleanup.json",
            EvidenceJson.canonicalBytes(Map.of("client", run.clientCleanup(), "clientChildren", run.clientChildren(),
                "server", run.serverCleanup(), "serverChildren", run.serverChildren())));
    }

    private static List<String> systemEndpointCommand(Path javaExecutable, Path classpathJar, String mainClass,
        int port, EndpointPaths paths, Path input, Path nativeLibrary, String scenarioId, String mode) {
        var result = new ArrayList<String>();
        result.add(javaExecutable.toString());
        result.add("-Djava.awt.headless=true");
        result.addAll(ENDPOINT_JVM_ARGUMENTS);
        result.add("-cp");
        result.add(classpathJar.toString());
        result.add(mainClass);
        result.addAll(systemEndpointArguments(port, paths, input, nativeLibrary, scenarioId, mode));
        return List.copyOf(result);
    }

    private static List<String> systemEndpointArguments(int port, EndpointPaths paths, Path input, Path nativeLibrary,
        String scenarioId, String mode) {
        return List.of(Integer.toString(port), paths.ready().toString(), paths.exit().toString(),
            paths.events().toString(), scenarioId, mode, paths.root().resolve("data").toString(), input.toString(),
            nativeLibrary.toString());
    }

    private static void writeSystemLaunchFailure(Path evidenceRoot, Throwable failure) throws IOException {
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        Files.writeString(evidenceRoot.resolve("classpath-system-launch-failure.txt"), trace.toString(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
    }

    private static PositiveRun runPositive(Path javaExecutable, List<Path> clientEntries, List<Path> serverEntries,
        EndpointPaths client, EndpointPaths server, int port) throws Exception {
        Files.createDirectories(client.workingDirectory());
        Files.createDirectories(server.workingDirectory());
        require(directoryEmpty(client.workingDirectory()) && directoryEmpty(server.workingDirectory()),
            "Endpoint working directories were not empty before launch");
        writeClasspathJar(client.launchJar(), clientEntries);
        writeClasspathJar(server.launchJar(), serverEntries);
        Process serverProcess = null;
        Process clientProcess = null;
        ProcessSupervisor.CleanupResult serverCleanup = null;
        ProcessSupervisor.CleanupResult clientCleanup = null;
        var serverExit = Integer.MIN_VALUE;
        var clientExit = Integer.MIN_VALUE;
        var serverChildren = List.<ChildProcess>of();
        var clientChildren = List.<ChildProcess>of();
        try {
            serverProcess = ProcessSupervisor.launchJava(javaExecutable, ENDPOINT_JVM_ARGUMENTS, server.launchJar(),
                SERVER_MAIN, endpointArguments(port, server), server.workingDirectory(), Map.of(), server.stdout());
            ProcessSupervisor.awaitFile(server.ready(), serverProcess, READY_GUARD);
            serverChildren = childProcesses(serverProcess);
            clientProcess = ProcessSupervisor.launchJava(javaExecutable, ENDPOINT_JVM_ARGUMENTS, client.launchJar(),
                CLIENT_MAIN, endpointArguments(port, client), client.workingDirectory(), Map.of(), client.stdout());
            ProcessSupervisor.awaitFile(client.ready(), clientProcess, READY_GUARD);
            clientChildren = childProcesses(clientProcess);
            clientExit = ProcessSupervisor.awaitExit(clientProcess, EXIT_GUARD);
            serverExit = ProcessSupervisor.awaitExit(serverProcess, EXIT_GUARD);
        } finally {
            clientCleanup = ProcessSupervisor.terminateExact(clientProcess, Duration.ofSeconds(2));
            serverCleanup = ProcessSupervisor.terminateExact(serverProcess, Duration.ofSeconds(2));
        }
        return new PositiveRun(clientExit, serverExit, clientCleanup, serverCleanup, clientChildren, serverChildren,
            directoryEmpty(client.workingDirectory()), directoryEmpty(server.workingDirectory()),
            readObservations(client.events()), readObservations(server.events()));
    }

    private static NegativeRun runMissingDependency(Path javaExecutable, String side, String mainClass,
        List<Path> entries, Path ownOutput, Path oppositeOutput, Path root) throws Exception {
        Files.createDirectories(root);
        var working = root.resolve("work");
        Files.createDirectories(working);
        require(directoryEmpty(working), "Negative working directory was not empty");
        var quickbuf = entries.stream().filter(path -> path.getFileName().toString().toLowerCase().contains("quickbuf"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No quickbuf dependency in " + side + " physical classpath"));
        var negativeEntries = new ArrayList<Path>();
        for (var entry : entries) {
            if (!entry.equals(quickbuf)) {
                negativeEntries.add(entry);
            }
        }
        if (!negativeEntries.contains(oppositeOutput)) {
            negativeEntries.add(oppositeOutput);
        }
        require(negativeEntries.contains(ownOutput), "Negative run accidentally removed its endpoint output");
        var launchJar = root.resolve("launch-classpath.jar");
        var ready = root.resolve("ready.json");
        var exit = root.resolve("exit.json");
        var events = root.resolve("events.jsonl");
        var stdout = root.resolve("stdout.log");
        writeClasspathJar(launchJar, negativeEntries);
        Process process = null;
        ProcessSupervisor.CleanupResult cleanup;
        int exitCode;
        try {
            process = ProcessSupervisor.launchJava(javaExecutable, ENDPOINT_JVM_ARGUMENTS, launchJar, mainClass,
                List.of("1", ready.toString(), exit.toString(), events.toString(), SCENARIO_ID), working, Map.of(),
                stdout);
            exitCode = ProcessSupervisor.awaitExit(process, READY_GUARD);
        } finally {
            cleanup = ProcessSupervisor.terminateExact(process, Duration.ofSeconds(2));
        }
        return new NegativeRun(side, quickbuf.toString(), oppositeOutput.toString(), exitCode, Files.exists(ready),
            cleanup, stdout.toString(), directoryEmpty(working));
    }

    private static void verifyPositive(PositiveRun run, MockManifest client, MockManifest server) throws IOException {
        require(run.clientExit() == 0 && run.serverExit() == 0,
            "Positive endpoint exit was not zero: client=" + run.clientExit() + " server=" + run.serverExit());
        require(run.clientCleanup().clean() && run.serverCleanup().clean() && !run.clientCleanup().forced()
            && !run.serverCleanup().forced(), "Positive endpoint cleanup was forced or left a process alive");
        require(
            run.clientChildren().stream().allMatch(ChildProcess::osInfrastructure)
                && run.serverChildren().stream().allMatch(ChildProcess::osInfrastructure),
            "An ordinary endpoint launched an unattributed child process");
        require(run.clientWorkingDirectoryEmpty() && run.serverWorkingDirectoryEmpty(),
            "An ordinary endpoint wrote into its empty working directory");
        require(client.aggregateSha256() != null && server.aggregateSha256() != null,
            "Classpath aggregate hashes were not recorded");
        for (var action : List.of("hello", "session-response", "publication-0", "publication-1", "publication-2",
            "publication-3", "select-request", "select-response")) {
            verifyFramePair(action, run.clientEvents(), run.serverEvents());
        }
        require(hasEvent(run.clientEvents(), "owner-close") && hasEvent(run.serverEvents(), "owner-close"),
            "A production session owner did not report close");
        require(
            hasEvent(run.clientEvents(), "session-publication-active")
                && hasEvent(run.serverEvents(), "session-publication-committed"),
            "The production publication path did not commit on both sides");
    }

    private static void verifyNegative(NegativeRun run) throws IOException {
        require(run.exitCode() != 0 && !run.readyObserved(),
            run.side() + " missing-dependency run did not fail before ready");
        require(run.cleanup().clean() && !run.cleanup().forced(),
            run.side() + " missing-dependency cleanup was forced or incomplete");
        require(run.workingDirectoryEmpty(), run.side() + " negative run wrote to its empty working directory");
        var output = new String(Files.readAllBytes(Path.of(run.stdout())), StandardCharsets.UTF_8);
        require(
            output.contains("quickbuf") || output.contains("ProtoMessage") || output.contains("NoClassDefFoundError"),
            run.side() + " negative failure did not identify the removed dependency");
    }

    private static void verifyFramePair(String action, List<Observed> client, List<Observed> server)
        throws IOException {
        var left = frame(client, action);
        var right = frame(server, action);
        require(!left.eventKind().equals(right.eventKind()),
            "Typed frame pair did not contain one send and one receive: " + action);
        require(left.payload().get("sha256").equals(right.payload().get("sha256")),
            "Typed frame bytes changed across the carrier: " + action);
        require(left.payload().get("messageType").equals(right.payload().get("messageType")),
            "Typed frame identity changed across the carrier: " + action);
    }

    private static Observed frame(List<Observed> events, String action) throws IOException {
        var matches = events.stream()
            .filter(event -> event.actionId().equals(action) && event.eventKind().startsWith("typed-frame-")).toList();
        require(matches.size() == 1, "Expected one typed frame observation for " + action);
        return matches.get(0);
    }

    private static boolean hasEvent(List<Observed> events, String kind) {
        return events.stream().anyMatch(event -> event.eventKind().equals(kind));
    }

    private static void retainRootArtifacts(ScenarioEvidence scenario, Path root) throws IOException {
        for (var name : List.of("classpath.client.json", "classpath.server.json")) {
            scenario.retainArtifact(name, Files.readAllBytes(root.resolve(name)));
        }
    }

    private static void retainPositiveArtifacts(ScenarioEvidence scenario, PositiveRun positive, EndpointPaths client,
        EndpointPaths server) throws IOException {
        for (var path : List.of(client.ready(), client.exit(), client.events(), client.stdout(), server.ready(),
            server.exit(), server.events(), server.stdout())) {
            var role = path.startsWith(client.root()) ? "client" : "server";
            scenario.retainArtifact("processes/" + role + "/" + path.getFileName(), Files.readAllBytes(path));
        }
        scenario.retainArtifact("processes/cleanup.json",
            EvidenceJson
                .canonicalBytes(Map.of("client", positive.clientCleanup(), "clientChildren", positive.clientChildren(),
                    "server", positive.serverCleanup(), "serverChildren", positive.serverChildren())));
    }

    private static void appendEndpointEvents(ScenarioEvidence scenario, List<Observed> events) throws IOException {
        for (var event : events) {
            scenario.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, event.actionId(), event.role(),
                event.connectionId(), event.operationId(), event.eventKind(), event.payload()));
        }
    }

    private static List<Observed> readObservations(Path path) throws IOException {
        var result = new ArrayList<Observed>();
        for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var source = JsonParser.parseString(line).getAsJsonObject();
            var payload = new LinkedHashMap<String, String>();
            source.getAsJsonObject("payload").entrySet()
                .forEach(entry -> payload.put(entry.getKey(), entry.getValue().getAsString()));
            result.add(new Observed(source.get("actionId").getAsString(), source.get("role").getAsString(),
                nullableString(source, "connectionId"), nullableString(source, "operationId"),
                source.get("eventKind").getAsString(), Map.copyOf(payload)));
        }
        return List.copyOf(result);
    }

    private static String nullableString(JsonObject source, String name) {
        return !source.has(name) || source.get(name).isJsonNull() ? null : source.get(name).getAsString();
    }

    private static void recordEvidenceSelfTest(EvidenceRun run) throws IOException {
        var input = EvidenceJson
            .canonicalBytes(Map.of("actions", List.of("record-current-identity", "seal-verdict"), "expected", "Pass"));
        var identity = run.identity("i08-evidence-self-test", List.of(), List.of("CO-016", "CO-018"),
            EvidenceRun.Radius.UNIT, false, input);
        var scenario = run.scenario(identity, input);
        scenario.append(new JsonlEvidenceSink.Observation(identity.scenarioId(), "record-current-identity", "evidence",
            null, "i08-self-test", "revision-identity",
            Map.of("contract", "2", "decision", "3", "implementation", "15", "taskContext", "3")));
        scenario.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS,
            List.of(
                "Current C3/D3/C2/I15 metadata, append-only event, verdict, inventory, and index linkage were sealed"),
            List.of(), Map.of("ERROR", 0L, "contractViolation", 0L),
            "Evidence substrate identity self-test; no model-management product behavior was exercised"));
    }

    private static MockManifest classpathManifest(String side, String configuration, List<Path> entries,
        List<String> launchCommand, Path ownOutput, Path oppositeOutput) throws IOException {
        var manifestEntries = new ArrayList<MockEntry>();
        for (var entry : entries) {
            var directory = Files.isDirectory(entry);
            manifestEntries
                .add(new MockEntry(entry.toString(), directory ? "directory" : "file", size(entry), hash(entry)));
        }
        var aggregate = EvidenceJson.sha256(EvidenceJson.canonicalBytes(manifestEntries));
        return new MockManifest(side, configuration,
            Map.of("net.neoforged.distribution", side.equals("client") ? "client" : "server", "org.gradle.usage",
                "java-runtime"),
            List.copyOf(manifestEntries), aggregate, launchCommand, entries.contains(ownOutput),
            !entries.contains(oppositeOutput));
    }

    private static List<Path> readEntries(Path path) throws IOException {
        var entries = new LinkedHashSet<Path>();
        for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var entry = Path.of(line).toAbsolutePath().normalize();
            require(Files.exists(entry), "Classpath entry does not exist: " + entry);
            entries.add(entry);
        }
        require(!entries.isEmpty(), "Classpath entry file was empty: " + path);
        return List.copyOf(entries);
    }

    private static String hash(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return EvidenceJson.sha256(Files.readAllBytes(path));
        }
        var lines = new ArrayList<String>();
        try (var stream = Files.walk(path)) {
            for (var file : stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(file -> normalize(path.relativize(file)))).toList()) {
                lines.add(EvidenceJson.sha256(Files.readAllBytes(file)) + "  " + normalize(path.relativize(file)));
            }
        }
        return EvidenceJson.sha256((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static long size(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException error) {
                    throw new UncheckedIo(error);
                }
            }).sum();
        } catch (UncheckedIo error) {
            throw error.cause;
        }
    }

    private static void writeClasspathJar(Path path, List<Path> entries) throws IOException {
        Files.createDirectories(path.getParent());
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.CLASS_PATH, entries.stream().map(entry -> entry.toUri().toASCIIString())
            .reduce((left, right) -> left + " " + right).orElseThrow());
        try (var output = new JarOutputStream(
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), manifest)) {
            // The manifest is the complete physical classpath indirection.
        }
    }

    private static List<String> endpointCommand(Path javaExecutable, Path classpathJar, String mainClass, int port,
        EndpointPaths paths) {
        var result = new ArrayList<String>();
        result.add(javaExecutable.toString());
        result.add("-Djava.awt.headless=true");
        result.addAll(ENDPOINT_JVM_ARGUMENTS);
        result.add("-cp");
        result.add(classpathJar.toString());
        result.add(mainClass);
        result.addAll(endpointArguments(port, paths));
        return List.copyOf(result);
    }

    private static List<String> endpointArguments(int port, EndpointPaths paths) {
        return List.of(Integer.toString(port), paths.ready().toString(), paths.exit().toString(),
            paths.events().toString(), SCENARIO_ID);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static List<ChildProcess> childProcesses(Process process) {
        return process.toHandle().descendants().map(handle -> {
            var command = handle.info().command().orElse("");
            var windowsConsoleHost = System.getProperty("os.name").startsWith("Windows")
                && command.replace('\\', '/').toLowerCase().endsWith("/conhost.exe");
            return new ChildProcess(handle.pid(), command, windowsConsoleHost);
        }).toList();
    }

    private static boolean directoryEmpty(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private static JsonlEvidenceSink.Observation observation(String action, String role, String event,
        Map<String, String> payload) {
        return new JsonlEvidenceSink.Observation(SCENARIO_ID, action, role, null, "classpath-supervisor", event,
            payload);
    }

    private static void writeNew(Path path, Object value) throws IOException {
        Files.write(path, EvidenceJson.canonicalBytes(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private record MockManifest(String physicalSide, String gradleConfiguration, Map<String, String> attributes,
        List<MockEntry> entries, String aggregateSha256, List<String> launchCommand, boolean ownEndpointOutputPresent,
        boolean oppositeEndpointOutputAbsent) {
    }

    private record MockEntry(String canonicalPath, String kind, long sizeBytes, String sha256) {
    }

    private record EndpointPaths(Path root, Path workingDirectory, Path launchJar, Path ready, Path exit, Path events,
        Path stdout) {
        private static EndpointPaths create(Path evidenceRoot, String role) {
            var root = evidenceRoot.resolve("processes").resolve(role);
            return new EndpointPaths(root, root.resolve("work"), root.resolve("launch-classpath.jar"),
                root.resolve("ready.json"), root.resolve("exit.json"), root.resolve("observations.jsonl"),
                root.resolve("stdout.log"));
        }
    }

    private record Observed(String actionId, String role, String connectionId, String operationId, String eventKind,
        Map<String, String> payload) {
    }

    private record PositiveRun(int clientExit, int serverExit, ProcessSupervisor.CleanupResult clientCleanup,
        ProcessSupervisor.CleanupResult serverCleanup, List<ChildProcess> clientChildren,
        List<ChildProcess> serverChildren, boolean clientWorkingDirectoryEmpty, boolean serverWorkingDirectoryEmpty,
        List<Observed> clientEvents, List<Observed> serverEvents) {
    }

    private record SystemRun(int clientExit, int serverExit, ProcessSupervisor.CleanupResult clientCleanup,
        ProcessSupervisor.CleanupResult serverCleanup, List<ChildProcess> clientChildren,
        List<ChildProcess> serverChildren, boolean clientWorkingDirectoryEmpty, boolean serverWorkingDirectoryEmpty,
        List<Observed> clientEvents, List<Observed> serverEvents, EndpointPaths clientPaths, EndpointPaths serverPaths,
        byte[] input) {
    }

    private record ChildProcess(long pid, String command, boolean osInfrastructure) {
    }

    private record NegativeRun(String side, String removedDependency, String oppositeEndpointOutput, int exitCode,
        boolean readyObserved, ProcessSupervisor.CleanupResult cleanup, String stdout, boolean workingDirectoryEmpty) {
    }

    private static final class UncheckedIo extends RuntimeException {
        private final IOException cause;

        private UncheckedIo(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
