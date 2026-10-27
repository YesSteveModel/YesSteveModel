package com.elfmcys.ysm.mock.supervisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses the host probe protocol without requiring a model-management task package. */
public final class MolangHostSupervisor {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static Path entityFixtures;
    private static boolean observeEntities;
    private static boolean guiPurposeOnly;
    private static boolean catalogProgressOnly;

    public static void main(String[] arguments) throws Exception {
        var project = Path.of(arguments[0]);
        var root = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (!Path.of(arguments[1]).isAbsolute() || Files.exists(root)) {
            throw new IllegalArgumentException("A new absolute evidence directory is required");
        }
        Files.createDirectories(root);
        var processes = new ArrayList<Process>();
        var receipt = new LinkedHashMap<String, Object>();
        var failures = arguments.length > 2 && arguments[2].equals("failures");
        var lifecycleOnly = arguments.length > 2 && arguments[2].equals("entity-lifecycle");
        var rolesOnly = arguments.length > 2 && arguments[2].equals("entity-roles");
        var optionalOnly = arguments.length > 2 && arguments[2].equals("entity-optional");
        var opportunitiesOnly = arguments.length > 2 && arguments[2].equals("entity-opportunities");
        catalogProgressOnly = arguments.length > 2 && arguments[2].equals("entity-catalog-progress");
        guiPurposeOnly = arguments.length > 2 && arguments[2].equals("entity-gui-purpose");
        var entityState = arguments.length > 2 && (arguments[2].equals("entity-state") || lifecycleOnly || rolesOnly
            || optionalOnly || opportunitiesOnly || guiPurposeOnly);
        observeEntities = entityState;
        if (arguments.length > 3 && !arguments[3].isBlank())
            entityFixtures = Path.of(arguments[3]);
        receipt.put("boundary",
            "Isolated real Forge server/client command/watch, audio backend, reconnect and optional generated shared-player failure. Not exhaustive entity, default-fault, explicit retry/reload or disk-state acceptance.");
        try (var reservation = new java.net.ServerSocket(0)) {
            var port = reservation.getLocalPort();
            reservation.close();
            var address = "127.0.0.1:" + port;
            var server = launch(project, root, "server", address, entityState);
            processes.add(server);
            ProcessSupervisor.awaitFile(root.resolve("server/ready.json"), server, TIMEOUT);
            var client = launch(project, root, "client-a", address, entityState);
            processes.add(client);
            ProcessSupervisor.awaitFile(root.resolve("client-a/ready.json"), client, TIMEOUT);
            receipt.put("connected", action(root, client, "01-connected", "await-state", Map.of("state", "ACTIVE")));
            receipt.put("before", action(root, client, "02-before", "snapshot", Map.of()));
            if (catalogProgressOnly) {
                receipt.put("boundary",
                    "Actual Local Catalog HUD/page draws with controlled real scan checkpoints, multiplayer and integrated worlds; pending FULL contrast.");
                receipt.put("multiplayer", action(root, client, "progress-multiplayer", "catalog-progress",
                    Map.of("output", root.resolve("multiplayer-draws").toString())));
                receipt.put("remoteMultiplayer", remoteProgress(project, root, client, server, "multiplayer"));
                action(root, client, "catalog-disconnect", "catalog-disconnect", Map.of());
                action(root, server, "08-stop", "stop", Map.of());
                receipt.put("serverExit", ProcessSupervisor.awaitExit(server, TIMEOUT));
                var world = root.resolve("server/game/world");
                var integrated = root.resolve("client-a/game/saves/catalog-progress");
                try (var files = Files.walk(world)) {
                    for (var file : files.toList()) {
                        var destination = integrated.resolve(world.relativize(file).toString());
                        if (Files.isDirectory(file))
                            Files.createDirectories(destination);
                        else
                            Files.copy(file, destination);
                    }
                }
                action(root, client, "integrated-start", "integrated-start", Map.of("world", "catalog-progress"));
                receipt.put("integrated", action(root, client, "progress-integrated", "catalog-progress",
                    Map.of("output", root.resolve("integrated-draws").toString())));
                receipt.put("remoteIntegrated", remoteProgress(project, root, client, null, "integrated"));
                receipt.put("clientStop", action(root, client, "07-stop", "stop", Map.of()));
                receipt.put("clientExit", ProcessSupervisor.awaitExit(client, TIMEOUT));
                receipt.put("status", "passed");
                return;
            }
            if (guiPurposeOnly) {
                receipt.put("boundary",
                    "Real Forge GUI purpose clicks, rendering and installation observations; remaining races are reported by the page probe.");
                receipt.put("guiPurpose", action(root, client, "gui-purpose", "entity-gui-purpose",
                    Map.of("path", "local:custom/molang/fault", "output", root.toString())));
                receipt.put("clientStop", action(root, client, "07-stop", "stop", Map.of()));
                receipt.put("serverStop", action(root, server, "08-stop", "stop", Map.of()));
                receipt.put("clientExit", ProcessSupervisor.awaitExit(client, TIMEOUT));
                receipt.put("serverExit", ProcessSupervisor.awaitExit(server, TIMEOUT));
                receipt.put("status", "passed");
                return;
            }
            if (opportunitiesOnly) {
                for (var camera : new String[] {"FIRST_PERSON", "THIRD_PERSON_FRONT"}) {
                    var id = "local-pending-" + (camera.equals("FIRST_PERSON") ? "first" : "third");
                    var path = "entity/pending-" + id;
                    var admission = root.resolve("client-a/" + id + "-admission.txt");
                    var input = Map.of("camera", camera, "subject", "local", "path", path, "observeOnly", "true",
                        "admissionFile", admission.toString());
                    Files.writeString(root.resolve("client-a/action.json"),
                        JSON.toJson(Map.of("id", id, "name", "entity-pending", "arguments", input)));
                    ProcessSupervisor.awaitFile(admission, client, TIMEOUT);
                    command(root, server, id + "-selection", "ysm model set YsmHostA \"" + path + "\" alternate true");
                    receipt.put(id, action(root, client, id, "entity-pending", input));
                }
                entityView(root, client, receipt, "arm-reextract", "FIRST_PERSON", false, false, false,
                    Map.of("hook", "invalidate-arm", "trace", "true"));
                receipt.put("reload", action(root, client, "entity-reload", "entity-reload", Map.of()));
                entityView(root, client, receipt, "after-reload", "THIRD_PERSON_FRONT", false, false, false);
                receipt.put("clientStop", action(root, client, "07-stop", "stop", Map.of()));
                receipt.put("serverStop", action(root, server, "08-stop", "stop", Map.of()));
                receipt.put("clientExit", ProcessSupervisor.awaitExit(client, TIMEOUT));
                receipt.put("serverExit", ProcessSupervisor.awaitExit(server, TIMEOUT));
                receipt.put("status", "passed");
                return;
            }
            if (optionalOnly) {
                command(root, server, "position-a", "tp YsmHostA 0 -60 0 0 0");
                command(root, server, "parts-select", "ysm model set YsmHostA \"entity/parts-a\" - true");
                entityView(root, client, receipt, "parts-ready", "FIRST_PERSON", false, false, false,
                    Map.of("ticks", "120"));
                receipt.put("gun",
                    actionRole(root, server, "server", "optional-gun", "optional-gun", Map.of("player", "YsmHostA")));
                receipt.put("arms", action(root, client, "optional-arms", "entity-optional-arms", Map.of()));
                entityView(root, client, receipt, "background-hook-left", "FIRST_PERSON", false, false, false,
                    Map.of("hook", "hide", "arm", "LEFT"));
                entityView(root, client, receipt, "background-hook-right", "FIRST_PERSON", false, false, false,
                    Map.of("hook", "hide", "arm", "RIGHT"));
                entityView(root, client, receipt, "body-prepare-failure", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("hook", "prepare-failure"));
                entityView(root, client, receipt, "living-hook-hide", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("hook", "living-hide"));
                entityView(root, client, receipt, "body-hook-retired", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("hook", "retire"));
                receipt.put("clientStop", action(root, client, "07-stop", "stop", Map.of()));
                receipt.put("serverStop", action(root, server, "08-stop", "stop", Map.of()));
                receipt.put("clientExit", ProcessSupervisor.awaitExit(client, TIMEOUT));
                receipt.put("serverExit", ProcessSupervisor.awaitExit(server, TIMEOUT));
                receipt.put("status", "passed");
                return;
            }
            if (rolesOnly) {
                receipt.put("guiOwner", action(root, client, "gui-owner", "entity-gui-owner", Map.of()));
                command(root, server, "scoped-arrow", "summon minecraft:arrow 0 -59 3 {NoGravity:1b}");
                command(root, server, "scoped-boat", "summon minecraft:boat 2 -60 3 {NoGravity:1b}");
                receipt.put("scoped", action(root, client, "scoped-owner", "entity-scoped", Map.of()));
                var observer = launch(project, root, "client-b", address, true);
                processes.add(observer);
                ProcessSupervisor.awaitFile(root.resolve("client-b/ready.json"), observer, TIMEOUT);
                actionRole(root, observer, "client-b", "observer-local", "await-state", Map.of("state", "LOCAL"));
                command(root, server, "position-a", "tp YsmHostA 0 -60 0 0 0");
                command(root, server, "position-b", "tp YsmHostB 0 -60 3 180 0");
                for (var camera : new String[] {"FIRST_PERSON", "THIRD_PERSON_FRONT"}) {
                    var suffix = camera.equals("FIRST_PERSON") ? "first" : "third";
                    pendingView(root, client, server, receipt, suffix, camera, false);
                }
                pendingView(root, client, server, receipt, "retired", "FIRST_PERSON", true);
                actionRole(root, observer, "client-b", "observer-stop", "stop", Map.of());
                ProcessSupervisor.awaitExit(observer, TIMEOUT);
                command(root, server, "lifecycle-select", "ysm model set YsmHostA \"molang/fault\" - true");
                entityView(root, client, receipt, "ready", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("ticks", "120"));
                receipt.put("worker", action(root, client, "worker", "entity-worker", Map.of()));
                receipt.put("reconnect", action(root, client, "reconnect", "disconnect-reconnect", Map.of()));
                entityView(root, client, receipt, "rejoined", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("ticks", "120"));
                command(root, server, "failure-arrow", "summon minecraft:arrow 0 -59 3 {NoGravity:1b}");
                command(root, server, "failure-boat", "summon minecraft:boat 2 -60 3 {NoGravity:1b}");
                var scopedAdmission = root.resolve("client-a/scoped-failure-admission.txt");
                var scopedInput = Map.of("mode", "failure", "admissionFile", scopedAdmission.toString());
                Files.writeString(root.resolve("client-a/action.json"),
                    JSON.toJson(Map.of("id", "scoped-failure", "name", "entity-scoped", "arguments", scopedInput)));
                ProcessSupervisor.awaitFile(scopedAdmission, client, TIMEOUT);
                receipt.put("invalidPublication", actionRole(root, server, "server", "invalid-publication",
                    "invalid-publication", Map.of("player", "YsmHostA")));
                receipt.put("scopedFailure", action(root, client, "scoped-failure", "entity-scoped", scopedInput));
                receipt.put("invalidPublicationState",
                    action(root, client, "invalid-state", "await-state", Map.of("state", "INTRINSIC_DEFAULT_ONLY")));
                entityView(root, client, receipt, "publication-failed", "THIRD_PERSON_FRONT", false, false, false);
                receipt.put("clientStop", action(root, client, "07-stop", "stop", Map.of()));
                receipt.put("serverStop", action(root, server, "08-stop", "stop", Map.of()));
                receipt.put("clientExit", ProcessSupervisor.awaitExit(client, TIMEOUT));
                receipt.put("serverExit", ProcessSupervisor.awaitExit(server, TIMEOUT));
                receipt.put("status", "passed");
                return;
            }
            if (entityState) {
                receipt.put("boundary",
                    "Real Forge body/arm cancellation, local suppression, remote assignment/pause, GUI and reconnect. Each observation retains its limits; no exhaustive G acceptance.");
                Process observer = null;
                if (!lifecycleOnly) {
                    entityView(root, client, receipt, "first", "FIRST_PERSON", false, false, false);
                    entityView(root, client, receipt, "left", "FIRST_PERSON", false, false, false,
                        Map.of("arm", "LEFT"));
                    entityView(root, client, receipt, "reset-first", "FIRST_PERSON", false, false, false,
                        Map.of("reset", "true"));
                    entityView(root, client, receipt, "reset-third", "THIRD_PERSON_FRONT", false, false, false,
                        Map.of("reset", "true"));
                    entityView(root, client, receipt, "zero-first", "FIRST_PERSON", false, false, false,
                        Map.of("reset", "true", "tickZero", "true"));
                    entityView(root, client, receipt, "zero-third", "THIRD_PERSON_FRONT", false, false, false,
                        Map.of("reset", "true", "tickZero", "true"));
                    entityView(root, client, receipt, "third", "THIRD_PERSON_FRONT", false, false, false);
                    entityView(root, client, receipt, "self-hidden", "FIRST_PERSON", true, false, false);
                    entityView(root, client, receipt, "hands-hidden", "FIRST_PERSON", false, false, true);
                    var vanilla = launchVanilla(project, root, address);
                    processes.add(vanilla);
                    ProcessSupervisor.awaitFile(root.resolve("vanilla/ready.json"), vanilla, TIMEOUT);
                    receipt.put("vanillaChannel", actionRole(root, server, "server", "vanilla-channel",
                        "player-channel", Map.of("player", "YsmVanilla")));
                    command(root, server, "vanilla-position-a", "tp YsmHostA 0 -60 0 0 0");
                    command(root, server, "vanilla-position", "tp YsmVanilla 0 -60 3 180 0");
                    entityView(root, client, receipt, "vanilla-unassigned", "FIRST_PERSON", false, false, false);
                    command(root, server, "vanilla-force", "ysm model set YsmVanilla \"molang/fault\" - true");
                    entityView(root, client, receipt, "vanilla-forced", "FIRST_PERSON", false, false, false);
                    receipt.put("vanillaStop", actionRole(root, vanilla, "vanilla", "vanilla-stop", "stop", Map.of()));
                    receipt.put("vanillaExit", ProcessSupervisor.awaitExit(vanilla, TIMEOUT));
                    observer = launch(project, root, "client-b", address, true);
                    processes.add(observer);
                    ProcessSupervisor.awaitFile(root.resolve("client-b/ready.json"), observer, TIMEOUT);
                    receipt.put("nonparticipant", actionRole(root, observer, "client-b", "entity-local", "await-state",
                        Map.of("state", "LOCAL")));
                    command(root, server, "entity-position-a", "tp YsmHostA 0 -60 0 0 0");
                    command(root, server, "entity-position-b", "tp YsmHostB 0 -60 3 180 0");
                    entityView(root, client, receipt, "local-subject", "FIRST_PERSON", false, false, false);
                    for (var camera : new String[] {"FIRST_PERSON", "THIRD_PERSON_FRONT"}) {
                        var suffix = camera.equals("FIRST_PERSON") ? "first" : "third";
                        pendingView(root, client, server, receipt, suffix, camera, false);
                    }
                    command(root, server, "entity-force", "ysm model set YsmHostB \"molang/fault\" - true");
                    entityView(root, client, receipt, "forced", "FIRST_PERSON", false, false, false);
                    entityView(root, client, receipt, "remote-hidden", "FIRST_PERSON", false, true, false);
                    command(root, server, "entity-latest", "ysm model set YsmHostB \"entity/other\" - true");
                    entityView(root, client, receipt, "remote-projection", "FIRST_PERSON", false, true, false);
                    entityView(root, client, receipt, "layered", "FIRST_PERSON", false, true, false,
                        Map.of("subjectDisabled", "true"));
                    entityView(root, client, receipt, "layered-global-cleared", "FIRST_PERSON", false, false, false);
                    entityView(root, client, receipt, "layered-cleared", "FIRST_PERSON", false, false, false,
                        Map.of("subjectDisabled", "false"));
                    entityView(root, client, receipt, "remote-restored", "FIRST_PERSON", false, false, false);
                    receipt.put("audio", action(root, client, "entity-audio", "entity-audio", Map.of()));
                    command(root, server, "restricted-select", "ysm model set YsmHostB \"entity/restricted\" - true");
                    entityView(root, client, receipt, "restricted-cached", "FIRST_PERSON", false, false, false,
                        Map.of("ticks", "120"));
                    receipt.put("restrict",
                        actionRole(root, server, "server", "restrict", "restricted", Map.of("enabled", "true")));
                    receipt.put("restrictedAuthorization",
                        actionRole(root, server, "server", "restricted-authorization", "authorization",
                            Map.of("player", "YsmHostA", "path", "entity/restricted")));
                    entityView(root, client, receipt, "restricted-denied", "FIRST_PERSON", false, false, false,
                        Map.of("resetRemote", "true", "ticks", "120"));
                    receipt.put("unrestrict",
                        actionRole(root, server, "server", "unrestrict", "restricted", Map.of("enabled", "false")));
                    command(root, server, "restricted-restore", "ysm model set YsmHostB \"entity/other\" - true");
                    entityView(root, client, receipt, "authorized-restored", "FIRST_PERSON", false, false, false,
                        Map.of("ticks", "120"));
                    if (entityFixtures != null) {
                        for (var fixture : new String[] {"parts-a", "missing-leftarm", "empty-left",
                            "missing-background"}) {
                            command(root, server, "parts-select-" + fixture,
                                "ysm model set YsmHostA " + "\"entity/" + fixture + "\" - true");
                            entityView(root, client, receipt, fixture + "-left", "FIRST_PERSON", false, false, false,
                                Map.of("arm", "LEFT", "ticks", "120", "reset", "true"));
                            entityView(root, client, receipt, fixture + "-right", "FIRST_PERSON", false, false, false,
                                Map.of("arm", "RIGHT"));
                        }
                        command(root, server, "empty-body-select",
                            "ysm model set YsmHostA \"entity/empty-body\" - true");
                        entityView(root, client, receipt, "empty-body", "THIRD_PERSON_FRONT", false, false, false,
                            Map.of("ticks", "120", "reset", "true"));
                    }
                    if (entityFixtures != null) {
                        command(root, server, "timeline-select", "ysm model set YsmHostA \"entity/parts-a\" - true");
                        entityView(root, client, receipt, "timeline-ready", "THIRD_PERSON_FRONT", false, false, false,
                            Map.of("ticks", "120"));
                        receipt.put("timeline", action(root, client, "entity-timeline", "entity-timeline", Map.of()));
                    }
                    entityView(root, client, receipt, "hook-hidden", "THIRD_PERSON_FRONT", false, false, false,
                        Map.of("hook", "hide"));
                    entityView(root, client, receipt, "hook-selected", "THIRD_PERSON_FRONT", false, false, false,
                        Map.of("hook", "select"));
                    entityView(root, client, receipt, "hook-invalidated", "THIRD_PERSON_FRONT", false, false, false,
                        Map.of("hook", "invalidate", "trace", "true"));
                    entityView(root, client, receipt, "hook-first-left", "FIRST_PERSON", false, false, false,
                        Map.of("hook", "hide", "arm", "LEFT"));
                    entityView(root, client, receipt, "hook-first-right", "FIRST_PERSON", false, false, false,
                        Map.of("hook", "hide", "arm", "RIGHT"));
                    pendingView(root, client, server, receipt, "retired", "FIRST_PERSON", true);
                }
                receipt.put("select",
                    action(root, client, "entity-select", "select-path", Map.of("path", "molang/fault")));
                receipt.put("selection", actionRole(root, server, "server", "entity-selection", "await-selection",
                    Map.of("player", "YsmHostA", "path", "molang/fault")));
                entityView(root, client, receipt, "selected", "THIRD_PERSON_FRONT", false, false, false);
                assertSelected(receipt, "selected");
                receipt.put("gui", action(root, client, "entity-gui", "preview-screen",
                    Map.of("path", "molang/fault", "screenshot", root.resolve("gui.png").toString())));
                if (observer != null) {
                    receipt.put("observerStop",
                        actionRole(root, observer, "client-b", "entity-stop-b", "stop", Map.of()));
                    receipt.put("observerExit", ProcessSupervisor.awaitExit(observer, TIMEOUT));
                }
            } else {
                receipt.put("output", action(root, client, "03-output", "molang-output",
                    Map.of("screenshot", root.resolve("output.png").toString())));
            }
            if (failures) {
                receipt.put("publication",
                    action(root, client, "03a-publication", "await-path", Map.of("path", "molang/fault")));
                receipt.put("select",
                    action(root, client, "03b-select", "select-path", Map.of("path", "molang/fault")));
                receipt.put("selection", actionRole(root, server, "server", "03c-selection", "await-selection",
                    Map.of("player", "YsmHostA", "path", "molang/fault")));
                var before = actionRole(root, server, "server", "03d-state-before", "molang-state",
                    Map.of("player", "YsmHostA"));
                receipt.put("serverBeforeRecord", before);
                receipt.put("recordFailure",
                    action(root, client, "03e-record", "molang-failure", Map.of("kind", "record")));
                var after =
                    actionRole(root, server, "server", "03f-state-after", "molang-state", Map.of("player", "YsmHostA"));
                receipt.put("serverAfterRecord", after);
                assertProtectedState(before, after);
            }
            receipt.put("reconnect", action(root, client, "04-reconnect", "disconnect-reconnect", Map.of()));
            receipt.put("retired", action(root, client, "05-retired", "late-old-owner", Map.of()));
            if (entityState) {
                entityView(root, client, receipt, "rejoined", "THIRD_PERSON_FRONT", false, false, false);
                assertSelected(receipt, "rejoined");
                receipt.put("worker", action(root, client, "entity-worker", "entity-worker", Map.of()));
                entityView(root, client, receipt, "worker-retired", "THIRD_PERSON_FRONT", false, false, false);
                receipt.put("workerReconnect",
                    action(root, client, "worker-reconnect", "disconnect-reconnect", Map.of()));
                entityView(root, client, receipt, "worker-rejoined", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("ticks", "120"));
                assertSelected(receipt, "worker-rejoined");
                receipt.put("reload", action(root, client, "entity-reload", "entity-reload", Map.of()));
                entityView(root, client, receipt, "reloaded", "THIRD_PERSON_FRONT", false, false, false);
                assertSelected(receipt, "reloaded");
                receipt.put("incompatibleHello", actionRole(root, server, "server", "incompatible-hello",
                    "incompatible-hello", Map.of("player", "YsmHostA")));
                receipt.put("failedSession",
                    action(root, client, "failed-session", "await-state", Map.of("state", "INTRINSIC_DEFAULT_ONLY")));
                entityView(root, client, receipt, "session-failed", "THIRD_PERSON_FRONT", false, false, false);
                receipt.put("failureReconnect",
                    action(root, client, "failed-reconnect", "disconnect-reconnect", Map.of()));
                entityView(root, client, receipt, "recovered", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("ticks", "120"));
                assertSelected(receipt, "recovered");
                receipt.put("invalidPublication", actionRole(root, server, "server", "invalid-publication",
                    "invalid-publication", Map.of("player", "YsmHostA")));
                receipt.put("invalidPublicationState", action(root, client, "invalid-publication-state", "await-state",
                    Map.of("state", "INTRINSIC_DEFAULT_ONLY")));
                entityView(root, client, receipt, "publication-failed", "THIRD_PERSON_FRONT", false, false, false);
                receipt.put("publicationReconnect",
                    action(root, client, "publication-reconnect", "disconnect-reconnect", Map.of()));
                entityView(root, client, receipt, "publication-recovered", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("ticks", "120"));
                assertSelected(receipt, "publication-recovered");
                entityView(root, client, receipt, "hook-retired", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("hook", "retire"));
                receipt.put("hookReconnect", action(root, client, "hook-reconnect", "disconnect-reconnect", Map.of()));
                entityView(root, client, receipt, "final-rejoined", "THIRD_PERSON_FRONT", false, false, false,
                    Map.of("ticks", "120"));
                assertSelected(receipt, "final-rejoined");
            }
            if (failures) {
                receipt.put("reselect",
                    action(root, client, "05a-reselect", "select-path", Map.of("path", "molang/fault")));
                receipt.put("reselection", actionRole(root, server, "server", "05b-selection", "await-selection",
                    Map.of("player", "YsmHostA", "path", "molang/fault")));
                var before = actionRole(root, server, "server", "05c-state-before", "molang-state",
                    Map.of("player", "YsmHostA"));
                receipt.put("serverBeforeArray", before);
                receipt.put("arrayFailure",
                    action(root, client, "05d-array", "molang-failure", Map.of("kind", "array")));
                var after =
                    actionRole(root, server, "server", "05e-state-after", "molang-state", Map.of("player", "YsmHostA"));
                receipt.put("serverAfterArray", after);
                assertProtectedState(before, after);
                receipt.put("serverProtectedStateUnchanged", true);
            }
            receipt.put("after", action(root, client, "06-after", "snapshot", Map.of()));
            receipt.put("clientStop", action(root, client, "07-stop", "stop", Map.of()));
            receipt.put("serverStop", action(root, server, "08-stop", "stop", Map.of()));
            receipt.put("clientExit", ProcessSupervisor.awaitExit(client, TIMEOUT));
            receipt.put("serverExit", ProcessSupervisor.awaitExit(server, TIMEOUT));
            receipt.put("status", "passed");
        } catch (Exception failure) {
            receipt.put("status", "unreviewable");
            receipt.put("failure", failure.toString());
            if (processes.size() == 2 && processes.stream().allMatch(Process::isAlive)) {
                try {
                    receipt.put("failureServerState", actionRole(root, processes.get(0), "server", "failure-state",
                        "molang-state", Map.of("player", "YsmHostA")));
                    receipt.put("failureClientStop", action(root, processes.get(1), "failure-stop", "stop", Map.of()));
                    receipt.put("failureServerStop",
                        actionRole(root, processes.get(0), "server", "failure-stop", "stop", Map.of()));
                    receipt.put("failureClientExit", ProcessSupervisor.awaitExit(processes.get(1), TIMEOUT));
                    receipt.put("failureServerExit", ProcessSupervisor.awaitExit(processes.get(0), TIMEOUT));
                } catch (Exception shutdownFailure) {
                    failure.addSuppressed(shutdownFailure);
                    receipt.put("failureShutdown", shutdownFailure.toString());
                }
            }
            throw failure;
        } finally {
            var cleanups = new ArrayList<Object>();
            for (var process : processes)
                cleanups.add(ProcessSupervisor.terminateExact(process, Duration.ofSeconds(3)));
            receipt.put("cleanup", cleanups);
            Files.writeString(root.resolve("receipt.json"), JSON.toJson(receipt) + "\n");
        }
    }

    private static void assertSelected(Map<String, Object> receipt, String window) {
        var expected = ((JsonObject)receipt.get("select")).getAsJsonObject("facts").get("modelId");
        var selected = ((JsonObject)receipt.get(window)).getAsJsonObject("facts").getAsJsonObject("players")
            .getAsJsonObject("YsmHostA");
        if (!expected.equals(selected.get("requested"))
            || !selected.get("installed").getAsString().equals("molang/fault")
            || !selected.get("relation").getAsString().equals("MATCHING")) {
            throw new IllegalStateException("Accepted selection was not consumed in " + window + ": " + selected);
        }
        var persisted = ((JsonObject)receipt.get("selection")).getAsJsonObject("facts");
        if (!expected.equals(persisted.get("persistentModelId"))) {
            throw new IllegalStateException("Accepted selection was not persisted: " + persisted);
        }
    }

    private static void entityView(Path root, Process client, Map<String, Object> receipt, String name, String camera,
        boolean self, boolean other, boolean hands) throws Exception {
        entityView(root, client, receipt, name, camera, self, other, hands, Map.of());
    }

    private static void pendingView(Path root, Process client, Process server, Map<String, Object> receipt,
        String suffix, String camera, boolean retire) throws Exception {
        var id = "pending-" + suffix;
        var path = "entity/pending-" + (retire ? "retire" : suffix);
        var admission = root.resolve("client-a/" + id + "-admission.txt");
        var input = Map.of("camera", camera, "subject", "remote", "path", path, "retire", Boolean.toString(retire),
            "admissionFile", admission.toString());
        Files.writeString(root.resolve("client-a/action.json"),
            JSON.toJson(Map.of("id", id, "name", "entity-pending", "arguments", input)));
        ProcessSupervisor.awaitFile(admission, client, TIMEOUT);
        command(root, server, id + "-selection", "ysm model set YsmHostB \"" + path + "\" alternate true");
        receipt.put(id, action(root, client, id, "entity-pending", input));
    }

    private static void entityView(Path root, Process client, Map<String, Object> receipt, String name, String camera,
        boolean self, boolean other, boolean hands, Map<String, String> extra) throws Exception {
        var input = new LinkedHashMap<String, String>();
        input.putAll(Map.of("camera", camera, "self", Boolean.toString(self), "other", Boolean.toString(other), "hands",
            Boolean.toString(hands), "ticks", switch (name) {
                case "forced", "vanilla-forced", "remote-restored", "selected", "reloaded", "rejoined",
                    "layered-cleared" -> "120";
                default -> "25";
            }, "screenshot", root.resolve(name + ".png").toString()));
        input.putAll(extra);
        receipt.put(name, action(root, client, "entity-" + name, "entity-presentation", input));
    }

    private static void command(Path root, Process server, String id, String command) throws Exception {
        var result = actionRole(root, server, "server", id, "command", Map.of("command", command));
        if (result.getAsJsonObject("facts").get("result").getAsInt() <= 0) {
            throw new IllegalStateException("Server command rejected: " + command);
        }
    }

    private static JsonObject remoteProgress(Path project, Path root, Process client, Process server, String mode)
        throws Exception {
        var id = "remote-progress-" + mode;
        var role = root.resolve("client-a");
        var armed = role.resolve(id + "-armed.txt");
        var reload = role.resolve(id + "-reload.txt");
        var arguments = Map.of("remote", "true", "output", root.resolve(id).toString(), "armedFile", armed.toString(),
            "reloadFile", reload.toString());
        Files.writeString(role.resolve("action.json"),
            JSON.toJson(Map.of("id", id, "name", "catalog-progress", "arguments", arguments)));
        ProcessSupervisor.awaitFile(armed, client, TIMEOUT);
        var game = root.resolve(server == null ? "client-a/game" : "server/game");
        var destination = game.resolve("ysm/custom/" + id);
        installFixture(project, destination);
        var manifest = destination.resolve("ysm.json");
        var json = JSON.fromJson(Files.readString(manifest), JsonObject.class);
        json.getAsJsonObject("metadata").addProperty("name", "Remote progress " + mode);
        Files.writeString(manifest, JSON.toJson(json));
        if (server == null)
            Files.writeString(reload, "reload");
        else
            actionRole(root, server, "server", id + "-reload", "catalog-reload", Map.of());
        return actionRole(root, client, "client-a", id, "catalog-progress", arguments);
    }

    private static Process launchVanilla(Path project, Path root, String address) throws Exception {
        var roleRoot = root.resolve("vanilla");
        var game = roleRoot.resolve("game");
        Files.createDirectories(game);
        return ProcessSupervisor.launchModDev(
            project.resolve("build/moddev/runMockHostVanilla.cmd"), game, Map.of("YSM_MOCK_ROLE", "vanilla",
                "YSM_MOCK_ROLE_DIR", roleRoot.toString(), "YSM_MOCK_SERVER_ADDRESS", address),
            roleRoot.resolve("console.log"));
    }

    private static Process launch(Path project, Path root, String role, String address, boolean entityState)
        throws Exception {
        var roleRoot = root.resolve(role);
        var game = roleRoot.resolve("game");
        Files.createDirectories(game.resolve("config"));
        if (role.equals("server")) {
            // Reuse the user's existing development-server agreement in the isolated fixture.
            var eula = Files.readString(project.resolve("run/server/eula.txt"));
            if (!eula.contains("eula=true"))
                throw new IllegalStateException("Existing development server EULA is not accepted");
            Files.writeString(game.resolve("eula.txt"), eula);
            Files.writeString(game.resolve("server.properties"), "online-mode=false\nserver-port="
                + address.substring(address.lastIndexOf(':') + 1)
                + "\nlevel-type=minecraft:flat\ndifficulty=peaceful\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\n");
            installFixture(project, game.resolve("ysm/custom/molang/fault"));
            if (entityState) {
                if (entityFixtures != null)
                    installEntityFixtures(game);
                var other = game.resolve("ysm/custom/entity/other");
                installFixture(project, other);
                var manifest = other.resolve("ysm.json");
                var json = JSON.fromJson(Files.readString(manifest), JsonObject.class);
                json.getAsJsonObject("metadata").addProperty("name", "Other Entity Fixture");
                Files.writeString(manifest, JSON.toJson(json));
                var restricted = game.resolve("ysm/auth/entity/restricted");
                installFixture(project, restricted);
                var restrictedManifest = restricted.resolve("ysm.json");
                var restrictedJson = JSON.fromJson(Files.readString(restrictedManifest), JsonObject.class);
                restrictedJson.getAsJsonObject("metadata").addProperty("name", "Restricted Entity Fixture");
                restrictedJson.getAsJsonObject("properties").addProperty("free", false);
                Files.writeString(restrictedManifest, JSON.toJson(restrictedJson));
            }
        } else {
            if (catalogProgressOnly) {
                var progress = game.resolve("ysm/custom/progress");
                installFixture(project, progress.resolve("first"));
                Files.writeString(progress.resolve("bad.mxc"), "broken input");
                Files.writeString(progress.resolve("ysm-pack.json"), "{\"name\":\"Progress inputs\"}");
                var manifest = progress.resolve("first/ysm.json");
                var json = JSON.fromJson(Files.readString(manifest), JsonObject.class);
                json.getAsJsonObject("metadata").addProperty("name", "Progress fixture");
                Files.writeString(manifest, JSON.toJson(json));
                var duplicate = progress.resolve("duplicate");
                try (var files = Files.walk(progress.resolve("first"))) {
                    for (var file : files.toList()) {
                        var target = duplicate.resolve(progress.resolve("first").relativize(file).toString());
                        if (Files.isDirectory(file))
                            Files.createDirectories(target);
                        else
                            Files.copy(file, target);
                    }
                }
            }
            Files.writeString(game.resolve("config/ysm-client.toml"),
                "[general]\nDisclaimerShow = false\n" + (catalogProgressOnly ? "ModelScreenEagerLoading = false\n" : "")
                    + "\n[network]\nSessionMode = \"" + (role.equals("client-b") ? "LOCAL" : "AUTO") + "\"\n");
        }
        var script = project.resolve("build/moddev/runMockHost"
            + (role.equals("server") ? "Server" : role.equals("client-b") ? "ClientB" : "ClientA") + ".cmd");
        return ProcessSupervisor.launchModDev(script, game,
            Map.of("YSM_MOCK_ROLE", role, "YSM_MOCK_ROLE_DIR", roleRoot.toString(), "YSM_MOCK_ACTION_FILE",
                roleRoot.resolve("action.json").toString(), "YSM_MOCK_SERVER_ADDRESS", address, "LOCALAPPDATA",
                (guiPurposeOnly || catalogProgressOnly)
                    ? roleRoot.resolve("global").toString() : System.getenv("LOCALAPPDATA")),
            roleRoot.resolve("console.log"),
            guiPurposeOnly && !role.equals("server")
                ? java.util.List
                    .of("-javaagent:" + project.resolve("build/model-management-mock/gui-entry-host-agent.jar"))
                : java.util.List.of());
    }

    private static JsonObject action(Path root, Process process, String id, String name, Map<String, String> arguments)
        throws Exception {
        var role = id.startsWith("08") ? "server" : "client-a";
        return actionRole(root, process, role, id, name, arguments);
    }

    private static JsonObject actionRole(Path root, Process process, String role, String id, String name,
        Map<String, String> arguments) throws Exception {
        var roleRoot = root.resolve(role);
        Files.writeString(roleRoot.resolve("action.json"),
            JSON.toJson(Map.of("id", id, "name", name, "arguments", arguments)));
        var ack = roleRoot.resolve("acks/" + id + ".json");
        ProcessSupervisor.awaitFile(ack, process, TIMEOUT);
        var result = JSON.fromJson(Files.readString(ack), JsonObject.class);
        if (!result.get("success").getAsBoolean())
            throw new IllegalStateException(result.toString());
        System.out.println("MOLANG_FORGE," + id + "," + result);
        return result;
    }

    private static void assertProtectedState(JsonObject before, JsonObject after) {
        for (var key : new String[] {"authority", "protectedState"}) {
            if (!before.getAsJsonObject("facts").get(key).equals(after.getAsJsonObject("facts").get(key))) {
                throw new IllegalStateException("Severe expression failure mutated server " + key);
            }
        }
    }

    private static void installEntityFixtures(Path game) throws Exception {
        for (var fixture : new String[] {"parts-a", "missing-leftarm", "missing-background", "empty-left", "empty-body",
            "pending-first", "pending-third", "pending-remote", "pending-retire", "gui-pending",
            "pending-local-pending-first", "pending-local-pending-third"}) {
            var source = entityFixtures.resolve("raw-packs").resolve(
                fixture.equals("missing-leftarm") || fixture.equals("missing-background") ? fixture : "parts-a");
            var destination = game.resolve("ysm/custom/entity/" + fixture);
            try (var files = Files.walk(source)) {
                for (var file : files.sorted().toList()) {
                    var target = destination.resolve(source.relativize(file).toString());
                    if (Files.isDirectory(file))
                        Files.createDirectories(target);
                    else
                        Files.copy(file, target);
                }
            }
            addObservationAnimations(destination.resolve("pose.animation.json"));
            if (fixture.startsWith("pending-") || fixture.equals("gui-pending")) {
                var manifest = destination.resolve("ysm.json");
                var json = JSON.fromJson(Files.readString(manifest), JsonObject.class);
                json.getAsJsonObject("metadata").addProperty("name", fixture);
                Files.copy(destination.resolve("markers.png"), destination.resolve("alternate.png"));
                json.getAsJsonObject("files").getAsJsonObject("player").getAsJsonArray("texture").add("alternate.png");
                Files.writeString(manifest, JSON.toJson(json));
            }
            if (fixture.equals("empty-left") || fixture.equals("empty-body")) {
                var file = destination.resolve("model.geo.json");
                var geometry = JSON.fromJson(Files.readString(file), JsonObject.class);
                for (var bone : geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                    .getAsJsonArray("bones")) {
                    var object = bone.getAsJsonObject();
                    if (fixture.equals("empty-body") || object.get("name").getAsString().startsWith("Left"))
                        object.remove("cubes");
                }
                Files.writeString(file, JSON.toJson(geometry));
            }
        }
    }

    private static void addObservationAnimations(Path file) throws Exception {
        var json = JSON.fromJson(Files.readString(file), JsonObject.class);
        var animations = json.getAsJsonObject("animations");
        var trace = new JsonObject();
        trace.addProperty("animation_length", 1);
        if (guiPurposeOnly) {
            trace.addProperty("loop", true);
            trace.add("bones", JSON.fromJson(
                "{\"Root\":{\"rotation\":{\"0.0\":[0,0,-45],\"0.5\":[0,0,45],\"1.0\":[0,0,-45]}}}", JsonObject.class));
        }
        var timeline = new JsonObject();
        for (var i = 1; i <= 4; i++)
            timeline.addProperty(Double.toString(i * 0.05), (guiPurposeOnly ? "v.gui_probe=0;" : "")
                + "r.trace=r.trace*10+" + i + ";ysm_mock_host.trace_event(" + i + ");");
        trace.add("timeline", timeline);
        animations.add("trace", trace);
        var worker = new JsonObject();
        worker.addProperty("animation_length", 1);
        var blocked = new JsonObject();
        blocked.addProperty("0.05", "ysm_mock_host.worker_gate();");
        worker.add("timeline", blocked);
        animations.add("worker", worker);
        Files.writeString(file, JSON.toJson(json));
    }

    private static void installFixture(Path project, Path destination) throws Exception {
        var source = project.resolve("src/main/resources/assets/ysm/builtin/misc/1_alex");
        try (var files = Files.walk(source)) {
            for (var file : files.sorted().toList()) {
                var target = destination.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file))
                    Files.createDirectories(target);
                else
                    Files.copy(file, target);
            }
        }
        if (observeEntities) {
            addObservationAnimations(destination.resolve("animations/main.animation.json"));
            var sounds = destination.resolve("sounds");
            Files.createDirectories(sounds);
            Files.copy(project.resolve("src/main/resources/assets/ysm/builtin/wine_fox/07_jk/sounds/minuet.ogg"),
                sounds.resolve("minuet.ogg"));
        }
        var manifest = destination.resolve("ysm.json");
        var json = JSON.fromJson(Files.readString(manifest), JsonObject.class);
        json.getAsJsonObject("metadata").addProperty("name", "Molang Failure Fixture");
        if (guiPurposeOnly) {
            Files.copy(destination.resolve("textures/gsl.png"), destination.resolve("textures/alternate.png"));
            json.getAsJsonObject("files").getAsJsonObject("player").getAsJsonArray("texture")
                .add("textures/alternate.png");
            for (var index = 1; index <= 3; index++) {
                var texture = "textures/variant" + index + ".png";
                Files.copy(destination.resolve("textures/gsl.png"), destination.resolve(texture));
                json.getAsJsonObject("files").getAsJsonObject("player").getAsJsonArray("texture").add(texture);
            }
        }
        Files.writeString(manifest, JSON.toJson(json));
    }
}
