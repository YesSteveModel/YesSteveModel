package com.elfmcys.ysm.mock.evidence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Serialized action and expectation authority for the classpath exact-100 scenario. */
public final class MockExact100Input {
    public static final String KEY = "workloadPlan";
    private static final Gson GSON = new Gson();

    private MockExact100Input() {}

    public static ScenarioInput create() {
        var serverHalf = Exact100WorkloadInput.create();
        var players = new ArrayList<PlayerPlan>(serverHalf.sessionCount());
        for (var session : serverHalf.sessions()) {
            var connections = switch (session.role()) {
                case "pressure" -> List.of(connection(session.initialConnection(), "pressure", true, true,
                    List.of("ready"), action(1, "metadata", "success"), action(2, "metadata", "success"),
                    action(3, "metadata", "success"), action(4, "metadata", "success"),
                    action(5, "metadata", "rejected:BUSY")));
                case "production-failure" -> List.of(connection(session.initialConnection(), "production-failure", true,
                    false, List.of("failed"), action(1, "metadata", "failure:UNAVAILABLE")));
                case "replace" -> List.of(
                    connection(session.initialConnection(), "replace-old", false, false, List.of("owner-shutdown"),
                        action(1, "metadata", "cancellation")),
                    connection(session.replacementConnection(), "replace-current", true, true, List.of("ready"),
                        action(1, "metadata", "success")));
                case "leave" -> List.of(connection(session.initialConnection(), "leave", false, false,
                    List.of("owner-shutdown"), action(1, "metadata", "cancellation")));
                case "cancel-rerequest" -> List.of(connection(session.initialConnection(), "cancel-rerequest", true,
                    true, List.of("cancellation", "ready"), action(1, "metadata", "cancellation"),
                    action(2, "metadata", "success")));
                case "unauthorized-then-grant" -> List.of(connection(session.initialConnection(),
                    "unauthorized-then-grant", true, true, List.of("ready"), action(1, "metadata", "success"),
                    action(2, "chunk", "rejected:UNAUTHORIZED"), action(3, "chunk", "success")));
                case "invalid" ->
                    List.of(connection(session.initialConnection(), "invalid", false, false, List.of("ready"),
                        action(1, "metadata", "success"), action(0, "chunk", "rejected:INTRINSIC_INVALID")));
                case "normal" -> List.of(connection(session.initialConnection(), "normal", true, true, List.of("ready"),
                    action(1, "metadata", "success")));
                default -> throw new AssertionError(session.role());
            };
            players.add(new PlayerPlan(session.index(), session.playerId(), connections));
        }
        return new ScenarioInput(serverHalf.seed(), serverHalf.sessionCount(),
            new SettingsInput(serverHalf.settings().softLimit(), serverHalf.settings().hardLimit(),
                serverHalf.settings().bytesPerSecond()),
            List.copyOf(players), List.of(new OptionalRun(200, "Not run", "future Bukkit characterization"),
                new OptionalRun(300, "Not run", "extreme characterization")));
    }

    public static ScenarioInput parse(JsonObject root) {
        if (!root.has(KEY) || !root.get(KEY).isJsonObject()) {
            throw new IllegalArgumentException("Classpath exact-100 input omitted " + KEY);
        }
        return GSON.fromJson(root.getAsJsonObject(KEY), ScenarioInput.class);
    }

    private static ConnectionPlan connection(String connectionId, String role, boolean current, boolean ready,
        List<String> activationTerminals, TransferAction... actions) {
        return new ConnectionPlan(connectionId, role, current, ready, activationTerminals, List.of(actions));
    }

    private static TransferAction action(long transferId, String request, String disposition) {
        return new TransferAction(transferId, request, disposition);
    }

    public record ScenarioInput(long seed, int playerCount, SettingsInput settings, List<PlayerPlan> players,
        List<OptionalRun> optionalRuns) {
    }

    public record SettingsInput(int softLimit, int hardLimit, long bytesPerSecond) {
    }

    public record PlayerPlan(int index, String playerId, List<ConnectionPlan> connections) {
    }

    public record ConnectionPlan(String connectionId, String role, boolean current, boolean ready,
        List<String> activationTerminals, List<TransferAction> actions) {
    }

    public record TransferAction(long transferId, String request, String disposition) {
    }

    public record OptionalRun(int players, String outcome, String purpose) {
    }
}
