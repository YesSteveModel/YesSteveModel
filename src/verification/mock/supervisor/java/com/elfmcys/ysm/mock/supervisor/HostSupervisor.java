package com.elfmcys.ysm.mock.supervisor;

import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class HostSupervisor {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String SCENARIO_ID = "MMR-FORGE-THIN-001";
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration ACTION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration EXIT_TIMEOUT = Duration.ofMinutes(2);
    private static final String PUBLIC_PATH = "host/online";
    private static final String PAGE_PACK = "host/";

    private final Path project;
    private final Path evidenceRoot;
    private final String javaRevision;
    private final Path nativeLibrary;
    private final int implementationRevision;
    private final Path serverScript;
    private final Path clientAScript;
    private final Path clientBScript;
    private final List<String> observations = new ArrayList<>();
    private final List<String> mismatches = new ArrayList<>();
    private final Map<String, Long> diagnosticCounts = new LinkedHashMap<>();
    private final List<Role> roles = new ArrayList<>();
    private ScenarioEvidence scenario;
    private boolean normalStop;

    private HostSupervisor(String[] args) {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                "Expected project, evidence, revision, native, " + "implementation revision and three launch scripts");
        }
        project = Path.of(args[0]).toAbsolutePath().normalize();
        evidenceRoot = Path.of(args[1]).toAbsolutePath().normalize();
        javaRevision = args[2];
        nativeLibrary = Path.of(args[3]).toAbsolutePath().normalize();
        implementationRevision = Integer.parseInt(args[4]);
        serverScript = Path.of(args[5]).toAbsolutePath().normalize();
        clientAScript = Path.of(args[6]).toAbsolutePath().normalize();
        clientBScript = Path.of(args[7]).toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        var outcome = new HostSupervisor(args).execute();
        if (outcome != ScenarioEvidence.Outcome.PASS) {
            throw new IllegalStateException("Forge host verdict: " + outcome);
        }
    }

    private ScenarioEvidence.Outcome execute() throws Exception {
        Throwable failure = null;
        var outcome = ScenarioEvidence.Outcome.PASS;
        try {
            openEvidence();
            runScenario();
        } catch (ProductFailure productFailure) {
            failure = productFailure;
            outcome = ScenarioEvidence.Outcome.FAIL;
            mismatches.add(productFailure.getMessage());
        } catch (Throwable infrastructureFailure) {
            failure = infrastructureFailure;
            outcome = ScenarioEvidence.Outcome.UNREVIEWABLE;
            mismatches.add(infrastructureFailure.getClass().getName() + ": "
                + Objects.toString(infrastructureFailure.getMessage(), ""));
        } finally {
            var cleanup = cleanup();
            diagnosticCounts.put("forcedProcessCleanups", cleanup.forcedCount());
            diagnosticCounts.put("remainingProcesses", cleanup.remainingCount());
            if (cleanup.remainingCount() != 0) {
                outcome = ScenarioEvidence.Outcome.UNREVIEWABLE;
                mismatches.add("Exact process-tree cleanup left live processes");
            }
            if (scenario != null) {
                retainArtifacts(cleanup, failure);
                scenario.complete(new ScenarioEvidence.Verdict(outcome, observations, mismatches, diagnosticCounts,
                    "Windows 11, Forge 47.4.3, Minecraft 1.20.1 and the configured "
                        + "native library only; shader packs, other GPUs, long soak and "
                        + "physical GPU/RSS reclamation were not run."));
            }
        }
        return outcome;
    }

    private void openEvidence() throws Exception {
        requireFile(nativeLibrary, "native library");
        requireFile(serverScript, "server launch script");
        requireFile(clientAScript, "client A launch script");
        requireFile(clientBScript, "client B launch script");
        var nativeRevision = gitRevision(nativeLibrary.getParent());
        var configuration = Map.of("forge", "47.4.3", "minecraft", "1.20.1", "nativeLibrarySha256",
            sha256(nativeLibrary), "scenario", "thin-host-adapter-conformance", "task", "modelManagementMockForge");
        var metadata = new EvidenceRun.Metadata(new EvidenceRun.Revisions(3, 3, 2, implementationRevision),
            Map.of("docs", "9d42e4013b52c4c6f18b6e56a0d50920251ab8dc", "java", javaRevision, "native", nativeRevision),
            configuration,
            Map.of("arch", System.getProperty("os.arch"), "java", System.getProperty("java.version"), "os",
                System.getProperty("os.name")),
            List.of("./gradlew modelManagementMockForge"), Instant.now().toString(), evidenceRoot.toString());
        var run = EvidenceRun.open(evidenceRoot, metadata);
        var input = canonicalInput();
        var identity = run.identity(
            SCENARIO_ID, List.of("FA-004", "FA-007", "FA-010"), List.of("CO-004", "CO-005", "CO-007", "CO-008",
                "CO-009", "CO-010", "CO-011", "CO-013", "CO-016", "CO-017", "CO-018"),
            EvidenceRun.Radius.FORGE_HOST, true, input);
        scenario = run.scenario(identity, input);
        observations.add("Fixed input and action manifest admitted before process launch");
    }

    private byte[] canonicalInput() throws Exception {
        var fixtureRoot = project.resolve("src/main/resources/assets/ysm/builtin/misc");
        var input = new LinkedHashMap<String, Object>();
        input.put("actions",
            List.of("client-a:await-state(ACTIVE)", "client-b:await-state(LOCAL)", "server:snapshot",
                "client-a:await-path(host/online)", "client-a:select-path(host/online)",
                "server:await-selection(YsmHostA,host/online)", "supervisor:add-page-pack", "server:await-pack(host/)",
                "client-a:page-pack-cover(host/)", "client-a:invalid-select", "client-a:disconnect-reconnect",
                "client-a:late-old-owner", "server:late-old-owner(YsmHostA)", "client-b:set-slot(4)",
                "server:await-slot(YsmHostB,4)", "all:snapshot", "all:normal-stop"));
        input.put("catalogLinksActions", List.of("startup-and-connect", "repeat-service-no-reload",
            "file-change-no-reload", "explicit-service-reload", "remote-hides-local-reload",
            "two-gpu-leases", "source-replacement-and-peer-release", "disconnect-latest-local",
            "dedicated-service-recreation", "integrated-connect-single-reload"));
        input.put("fixtures", Map.of("pagePackCover", sha256(fixtureRoot.resolve("ysm-pack.png")), "pagePackDescriptor",
            sha256(fixtureRoot.resolve("ysm-pack.json")), "public", treeHash(fixtureRoot.resolve("1_alex"))));
        input.put("isolated", true);
        input.put("nativeLibrarySha256", sha256(nativeLibrary));
        input.put("roles", List.of("server", "client-a", "client-b"));
        input.put("usernames", List.of("YsmHostA", "YsmHostB"));
        return EvidenceJson.canonicalBytes(input);
    }

    private void runScenario() throws Exception {
        var scenarioDirectory = evidenceRoot.resolve(SCENARIO_ID);
        var processRoot = scenarioDirectory.resolve("processes");
        var serverGame = processRoot.resolve("server/game");
        var clientAGame = processRoot.resolve("client-a/game");
        var clientBGame = processRoot.resolve("client-b/game");
        prepareServer(serverGame);
        prepareClient(clientAGame, "AUTO");
        prepareClient(clientBGame, "LOCAL");
        installFixture("1_alex", clientBGame.resolve("ysm/custom/local/resource"), "Local resource");
        var port = reservePort();
        var address = "127.0.0.1:" + port;
        Files.writeString(serverGame.resolve("server.properties"),
            "online-mode=false\nserver-port=" + port + "\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\n",
            StandardCharsets.UTF_8);

        var server = role("server", serverGame, serverScript, address);
        launch(server);
        awaitReady(server);
        var clientA = role("client-a", clientAGame, clientAScript, address);
        var clientB = role("client-b", clientBGame, clientBScript, address);
        launch(clientA);
        awaitReady(clientA);
        expect(send(clientA, "01-a-active", "await-state", Map.of("state", "ACTIVE")), "facts.state", "ACTIVE");

        launch(clientB);
        awaitReady(clientB);
        expect(send(clientB, "02-b-local", "await-state", Map.of("state", "LOCAL")), "facts.state", "LOCAL");
        var serverBefore = send(server, "catalog-server-before", "catalog-repeat-service", Map.of());
        var remoteBefore = send(clientA, "catalog-remote-before", "catalog-snapshot", Map.of());
        installFixture("2_steve", clientAGame.resolve("ysm/custom/local/after"), "Local hidden");
        var remoteAfter = send(clientA, "catalog-local-reload", "catalog-local-reload", Map.of());
        expect(remoteAfter, "facts.visiblePaths", at(remoteBefore.json(), "facts.visiblePaths"));
        expectContains(remoteAfter, "facts.localPaths", "local/after");
        send(clientB, "catalog-acquire-two", "catalog-leases-acquire", Map.of("path", "local/resource"));
        installFixture("2_steve", clientBGame.resolve("ysm/custom/local/resource"), "Replacement resource");
        send(clientB, "catalog-retire-source", "catalog-local-reload", Map.of());
        send(clientB, "catalog-use-retired", "catalog-leases-use", Map.of());
        expect(send(clientB, "catalog-release-peer", "catalog-lease-release-first", Map.of()), "facts.peerReleased", true);
        send(clientB, "catalog-close-leases", "catalog-leases-close", Map.of());
        var serverInitial = send(server, "03-server-initial", "snapshot", Map.of());
        expect(serverInitial, "facts.sessions.YsmHostA.active", true);
        expectNotTrue(serverInitial, "facts.sessions.YsmHostB.active");
        var publication = send(clientA, "04-public", "await-path", Map.of("path", PUBLIC_PATH));
        expect(publication, "facts.origin", "SERVER");
        expect(publication, "facts.path", PUBLIC_PATH);
        send(clientA, "05-select", "select-path", Map.of("path", PUBLIC_PATH));
        var selection =
            send(server, "06-selection", "await-selection", Map.of("path", PUBLIC_PATH, "player", "YsmHostA"));
        expect(selection, "facts.path", PUBLIC_PATH);
        expect(selection, "facts.modelId", at(publication.json(), "facts.modelId").getAsString());
        expect(selection, "facts.connection",
            at(serverInitial.json(), "facts.sessions.YsmHostA.connection").getAsString());
        observations.add("Correlated actual Forge hello/full and typed model selection across "
            + "the client/server physical connection owners");

        installPack(serverGame.resolve("ysm/custom/host"), "Host Verification Pack");
        var unchanged = send(server, "catalog-no-automatic-reload", "catalog-snapshot", Map.of());
        expect(unchanged, "facts.scanSequence", at(serverBefore.json(), "facts.scanSequence"));
        send(server, "catalog-explicit-service-reload", "catalog-reload", Map.of());
        expect(send(server, "07-page-pack-server", "await-pack", Map.of("hierarchy", PAGE_PACK)), "facts.rootKind",
            "CUSTOM");
        var pageCover = send(clientA, "08-page-cover", "page-pack-cover", Map.of("hierarchy", PAGE_PACK));
        expect(pageCover, "facts.batchClosed", true);
        expect(pageCover, "facts.nativeImageDecode", true);
        expect(pageCover, "facts.pageAsset", "remote-pack-cover");
        expect(pageCover, "facts.renderThread", true);
        expect(pageCover, "facts.textureReleased", true);
        expect(send(server, "catalog-preview-invalidation", "catalog-preview-invalidation", Map.of()),
            "facts.invalidatedAfterServiceReload", true);
        var invalid = send(clientA, "09-invalid-select", "invalid-select", Map.of());
        expect(invalid, "facts.catalogUnchanged", true);
        expect(invalid, "facts.minecraftConnected", true);

        var reconnect = send(clientA, "10-reconnect", "disconnect-reconnect", Map.of());
        expectAtLeast(reconnect, "facts.currentOrdinal", 2);
        var clientLate = send(clientA, "11-client-late", "late-old-owner", Map.of());
        expect(clientLate, "facts.oldGateAccepted", false);
        expect(clientLate, "facts.publicationUnchanged", true);
        var serverLate = send(server, "12-server-late", "late-old-owner", Map.of("player", "YsmHostA"));
        expect(serverLate, "facts.oldGateAccepted", false);
        expect(serverLate, "facts.currentSelectionUnchanged", true);
        expect(send(clientB, "13-vanilla-slot", "set-slot", Map.of("slot", "4")), "facts.networkPath",
            "vanilla-client-to-server");
        expect(send(server, "14-server-slot", "await-slot", Map.of("player", "YsmHostB", "slot", "4")),
            "facts.selectedSlot", 4);
        var clientAFinal = send(clientA, "15-a-final", "snapshot", Map.of());
        expect(clientAFinal, "facts.minecraftConnected", true);
        expect(clientAFinal, "facts.sessionState", "ACTIVE");
        var clientBFinal = send(clientB, "16-b-final", "snapshot", Map.of());
        expect(clientBFinal, "facts.minecraftConnected", true);
        expect(clientBFinal, "facts.sessionState", "LOCAL");
        var serverFinal = send(server, "17-server-final", "snapshot", Map.of());
        expect(serverFinal, "facts.sessions.YsmHostA.active", true);
        expectNotTrue(serverFinal, "facts.sessions.YsmHostB.active");

        var disconnected = send(clientA, "catalog-disconnect-remote", "catalog-disconnect", Map.of());
        expect(disconnected, "facts.sessionState", "LOCAL");
        expectContains(disconnected, "facts.visiblePaths", "local/after");
        var localBeforeIntegrated = send(clientB, "catalog-disconnect-local", "catalog-disconnect", Map.of());
        send(server, "catalog-new-dedicated-service", "catalog-restart-service", Map.of());
        send(server, "20-server-stop", "stop", Map.of());
        awaitExit(server);
        var world = serverGame.resolve("world");
        var integrated = clientBGame.resolve("saves/catalog-links");
        try (var files = Files.walk(world)) {
            for (var file : files.toList()) {
                var destination = integrated.resolve(world.relativize(file).toString());
                if (Files.isDirectory(file))
                    Files.createDirectories(destination);
                else
                    Files.copy(file, destination);
            }
        }
        send(clientB, "catalog-integrated-start", "integrated-start", Map.of("world", "catalog-links"));
        var afterIntegrated = send(clientB, "catalog-integrated-snapshot", "catalog-repeat-integrated", Map.of());
        expect(afterIntegrated, "facts.scanSequence", at(localBeforeIntegrated.json(), "facts.scanSequence").getAsLong() + 1);
        send(clientA, "18-a-stop", "stop", Map.of());
        send(clientB, "19-b-stop", "stop", Map.of());
        awaitExit(clientA);
        awaitExit(clientB);
        normalStop = true;
        observations.add("All three Forge processes acknowledged normal lifecycle stop and exited");
    }

    private Role role(String name, Path gameDirectory, Path script, String address) throws IOException {
        var directory = gameDirectory.getParent();
        Files.createDirectories(directory.resolve("acks"));
        var role = new Role(name, directory, gameDirectory, directory.resolve("action.json"),
            directory.resolve("stdout.log"), script, address);
        roles.add(role);
        return role;
    }

    private void launch(Role role) throws IOException {
        var environment = Map.of("YSM_MOCK_ACTION_FILE", role.actionFile().toString(), "YSM_MOCK_BUILD_REVISION",
            javaRevision, "YSM_MOCK_ROLE", role.name(), "YSM_MOCK_ROLE_DIR", role.directory().toString(),
            "YSM_MOCK_SERVER_ADDRESS", role.address());
        role.process = ProcessSupervisor.launchModDev(role.script(), role.gameDirectory(), environment, role.output());
        observations.add("Launched " + role.name() + " pid=" + role.process.pid());
    }

    private void awaitReady(Role role) throws Exception {
        ProcessSupervisor.awaitFile(role.directory().resolve("ready.json"), role.process, READY_TIMEOUT);
        observations.add(role.name() + " crossed its machine ready barrier");
    }

    private Ack send(Role role, String id, String name, Map<String, String> arguments) throws Exception {
        var action = EvidenceJson.canonicalBytes(Map.of("arguments", arguments, "id", id, "name", name));
        writeAtomically(role.actionFile(), action);
        var ackPath = role.directory().resolve("acks").resolve(id + ".json");
        ProcessSupervisor.awaitFile(ackPath, role.process, ACTION_TIMEOUT);
        var json = JsonParser.parseString(Files.readString(ackPath, StandardCharsets.UTF_8)).getAsJsonObject();
        var success = json.get("success").getAsBoolean();
        var ack = new Ack(role.name(), id, name, success, json);
        if (!success) {
            throw new ProductFailure(role.name() + " action " + id + " failed: " + textAt(json, "failure"));
        }
        observations.add(role.name() + " completed " + id + " (" + name + ")");
        return ack;
    }

    private void expect(Ack ack, String path, Object expected) throws ProductFailure {
        var actual = at(ack.json(), path);
        var expectedJson = GSON.toJsonTree(expected);
        if (!expectedJson.equals(actual)) {
            throw new ProductFailure(ack.id() + " expected " + path + "=" + expectedJson + " but observed " + actual);
        }
        observations.add(ack.id() + " machine oracle confirmed " + path + "=" + actual);
    }

    private void expectNotTrue(Ack ack, String path) throws ProductFailure {
        var actual = atOptional(ack.json(), path);
        if (actual != null && actual.isJsonPrimitive() && actual.getAsBoolean()) {
            throw new ProductFailure(ack.id() + " expected " + path + " absent or false but observed true");
        }
        observations.add(ack.id() + " machine oracle confirmed rejected session is not active");
    }

    private void expectAtLeast(Ack ack, String path, long minimum) throws ProductFailure {
        var actual = at(ack.json(), path);
        if (actual.getAsLong() < minimum) {
            throw new ProductFailure(ack.id() + " expected " + path + ">=" + minimum + " but observed " + actual);
        }
        observations.add(ack.id() + " machine oracle confirmed replacement ordinal " + actual);
    }

    private void expectContains(Ack ack, String path, String expected) throws ProductFailure {
        if (!at(ack.json(), path).getAsJsonArray().contains(GSON.toJsonTree(expected)))
            throw new ProductFailure(ack.id() + " missing " + path + "=" + expected);
        observations.add(ack.id() + " confirmed " + path + " contains " + expected);
    }

    private static JsonElement at(JsonObject object, String path) throws ProductFailure {
        var found = atOptional(object, path);
        if (found == null) {
            throw new ProductFailure("Missing machine fact " + path);
        }
        return found;
    }

    private static JsonElement atOptional(JsonObject object, String path) {
        JsonElement current = object;
        for (var segment : path.split("\\.")) {
            if (!current.isJsonObject() || !current.getAsJsonObject().has(segment)) {
                return null;
            }
            current = current.getAsJsonObject().get(segment);
        }
        return current;
    }

    private static String textAt(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsString() : "missing failure detail";
    }

    private void awaitExit(Role role) throws Exception {
        if (!role.process.waitFor(EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IOException(role.name() + " did not exit after normal stop acknowledgement");
        }
        role.exitCode = role.process.exitValue();
        if (role.exitCode != 0) {
            throw new IOException(role.name() + " exited with " + role.exitCode);
        }
    }

    private CleanupSummary cleanup() {
        var forced = 0L;
        var remaining = 0L;
        for (var role : roles) {
            try {
                var result = ProcessSupervisor.terminateExact(role.process, Duration.ofSeconds(10));
                role.cleanup = result;
                if (result.forced()) {
                    forced++;
                }
                remaining += result.remaining().size();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                remaining++;
            }
        }
        return new CleanupSummary(forced, remaining);
    }

    private void retainArtifacts(CleanupSummary cleanup, Throwable failure) throws IOException {
        if (failure != null) {
            scenario.retainFailureArtifact("failure.txt", stackTrace(failure));
        }
        var launchFacts = new LinkedHashMap<String, Object>();
        for (var role : roles) {
            launchFacts.put(role.name(), Map.of("address", role.address(), "command",
                ProcessSupervisor.parseModDevScript(role.script()).command().stream()
                    .map(value -> value.replace("%%%%", "%%")).toList(),
                "directory", role.gameDirectory().toString(), "pid", role.process == null ? -1 : role.process.pid(),
                "script", role.script().toString()));
            retainIfFile("roles/" + role.name() + "/stdout.log", role.output());
            retainIfFile("roles/" + role.name() + "/latest.log", role.gameDirectory().resolve("logs/latest.log"));
            retainIfFile("roles/" + role.name() + "/observations.raw.jsonl",
                role.directory().resolve("observations.jsonl"));
            retainRoleFiles(role.directory().resolve("acks"), "roles/" + role.name() + "/acks");
            retainRoleFiles(role.gameDirectory().resolve("crash-reports"), "roles/" + role.name() + "/crash-reports");
            importObservations(role);
        }
        scenario.retainArtifact("launches.json", EvidenceJson.canonicalBytes(launchFacts));
        scenario.retainArtifact("cleanup.json",
            EvidenceJson.canonicalBytes(Map.of("forcedRoleCount", cleanup.forcedCount(), "normalStopCompleted",
                normalStop, "remainingProcessCount", cleanup.remainingCount(), "roles",
                roles.stream()
                    .collect(Collectors.toMap(Role::name, role -> role.cleanup == null ? "not-started" : role.cleanup,
                        (left, right) -> left, LinkedHashMap::new)))));
        diagnosticCounts.put("hostObservationCount", observations.stream().count());
        diagnosticCounts.put("roleEventCount", countRoleEvents());
    }

    private void retainIfFile(String name, Path source) throws IOException {
        if (Files.isRegularFile(source)) {
            scenario.retainArtifact(name, Files.readAllBytes(source));
        }
    }

    private void retainRoleFiles(Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                var relative = directory.relativize(file).toString().replace('\\', '/');
                scenario.retainArtifact(prefix + "/" + relative, Files.readAllBytes(file));
            }
        }
    }

    private void importObservations(Role role) throws IOException {
        var source = role.directory().resolve("observations.jsonl");
        if (!Files.isRegularFile(source)) {
            return;
        }
        for (var line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var value = JsonParser.parseString(line).getAsJsonObject();
            var facts = value.getAsJsonObject("facts");
            var payload = new LinkedHashMap<String, String>();
            payload.put("recordedAt", value.get("recordedAt").getAsString());
            payload.put("sequence", value.get("sequence").getAsString());
            payload.put("thread", value.get("thread").getAsString());
            if (facts != null) {
                facts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> payload.put(entry.getKey(), entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString() : GSON.toJson(entry.getValue())));
            }
            var connection = facts != null && facts.has("connection") ? facts.get("connection").getAsString() : null;
            scenario.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, value.get("actionId").getAsString(),
                role.name(), connection, null, value.get("eventKind").getAsString(), payload));
        }
    }

    private long countRoleEvents() throws IOException {
        var count = 0L;
        for (var role : roles) {
            var source = role.directory().resolve("observations.jsonl");
            if (Files.isRegularFile(source)) {
                try (var lines = Files.lines(source, StandardCharsets.UTF_8)) {
                    count += lines.filter(line -> !line.isBlank()).count();
                }
            }
        }
        return count;
    }

    private void prepareServer(Path gameDirectory) throws Exception {
        Files.createDirectories(gameDirectory);
        Files.writeString(gameDirectory.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        installFixture("1_alex", gameDirectory.resolve("ysm/custom/host/online"), "Host Online");
    }

    private void prepareClient(Path gameDirectory, String mode) throws IOException {
        var config = gameDirectory.resolve("config/ysm-client.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[general]\nDisclaimerShow = false\n\n[network]\nSessionMode = \"" + mode + "\"\n",
            StandardCharsets.UTF_8);
    }

    private void installFixture(String sourceName, Path destination, String displayName) throws Exception {
        var source = project.resolve("src/main/resources/assets/ysm/builtin/misc").resolve(sourceName);
        try (var files = Files.walk(source)) {
            for (var file : files.sorted().toList()) {
                var target = destination.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var manifest = destination.resolve("ysm.json");
        var json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        json.getAsJsonObject("metadata").addProperty("name", displayName);
        Files.writeString(manifest, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private void installPack(Path destination, String displayName) throws Exception {
        var source = project.resolve("src/main/resources/assets/ysm/builtin/misc");
        Files.createDirectories(destination);
        Files.copy(source.resolve("ysm-pack.png"), destination.resolve("ysm-pack.png"),
            StandardCopyOption.REPLACE_EXISTING);
        var json = JsonParser.parseString(Files.readString(source.resolve("ysm-pack.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
        json.addProperty("name", displayName);
        Files.writeString(destination.resolve("ysm-pack.json"), GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private static int reservePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String treeHash(Path root) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/'))).toList()) {
                digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                digest.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String gitRevision(Path start) throws Exception {
        for (var candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (!Files.exists(candidate.resolve(".git"))) {
                continue;
            }
            var process = new ProcessBuilder("git", "-C", candidate.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return output;
            }
        }
        return "unavailable-local-native-revision";
    }

    private static void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing " + label + ": " + path);
        }
    }

    private static byte[] stackTrace(Throwable failure) {
        var writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record Ack(String role, String id, String name, boolean success, JsonObject json) {
    }

    private record CleanupSummary(long forcedCount, long remainingCount) {
    }

    private static final class ProductFailure extends Exception {
        private ProductFailure(String message) {
            super(message);
        }
    }

    private static final class Role {
        private final String name;
        private final Path directory;
        private final Path gameDirectory;
        private final Path actionFile;
        private final Path output;
        private final Path script;
        private final String address;
        private Process process;
        private Integer exitCode;
        private ProcessSupervisor.CleanupResult cleanup;

        private Role(String name, Path directory, Path gameDirectory, Path actionFile, Path output, Path script,
            String address) {
            this.name = name;
            this.directory = directory;
            this.gameDirectory = gameDirectory;
            this.actionFile = actionFile;
            this.output = output;
            this.script = script;
            this.address = address;
        }

        private String name() {
            return name;
        }

        private Path directory() {
            return directory;
        }

        private Path gameDirectory() {
            return gameDirectory;
        }

        private Path actionFile() {
            return actionFile;
        }

        private Path output() {
            return output;
        }

        private Path script() {
            return script;
        }

        private String address() {
            return address;
        }
    }
}
