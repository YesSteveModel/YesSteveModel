package com.elfmcys.ysm.mock.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Immutable exact-100 action input shared by the server-half and classpath runs. */
public final class Exact100WorkloadInput {
    public static final long SEED = 0x59_53_4d_10_00L;
    public static final int SESSION_COUNT = 100;

    private Exact100WorkloadInput() {}

    public static ScenarioInput create() {
        var sessions = new ArrayList<SessionPlan>(SESSION_COUNT);
        var current = new ArrayList<ExpectedConnection>();
        var accepted = new ArrayList<ExpectedWork>();
        var rejected = new ArrayList<ExpectedRejection>();
        var ready = new ArrayList<String>();
        for (var index = 0; index < SESSION_COUNT; index++) {
            var player = new UUID(SEED, index + 1L).toString();
            var initial = connection(index, 1);
            var replacement = index == 2 ? connection(index, 2) : null;
            var role = switch (index) {
                case 0 -> "pressure";
                case 1 -> "production-failure";
                case 2 -> "replace";
                case 3 -> "leave";
                case 4 -> "cancel-rerequest";
                case 5 -> "unauthorized-then-grant";
                case 6 -> "invalid";
                default -> "normal";
            };
            sessions.add(new SessionPlan(index, player, initial, replacement, role, actions(role)));
            switch (role) {
                case "pressure" -> {
                    current.add(new ExpectedConnection(player, initial, true));
                    for (var transferId = 1L; transferId <= 4; transferId++) {
                        accepted.add(new ExpectedWork(initial, transferId, "success"));
                    }
                    rejected.add(new ExpectedRejection(initial, 5, "BUSY"));
                }
                case "production-failure" -> {
                    current.add(new ExpectedConnection(player, initial, true));
                    accepted.add(new ExpectedWork(initial, 1, "failure:UNAVAILABLE"));
                }
                case "replace" -> {
                    current.add(new ExpectedConnection(player, replacement, true));
                    accepted.add(new ExpectedWork(initial, 1, "owner-shutdown"));
                    accepted.add(new ExpectedWork(replacement, 1, "success"));
                    accepted.add(new ExpectedWork(replacement, 2, "success"));
                    ready.add(replacement);
                }
                case "leave" -> {
                    current.add(new ExpectedConnection(player, null, false));
                    accepted.add(new ExpectedWork(initial, 1, "owner-shutdown"));
                    accepted.add(new ExpectedWork(initial, 2, "owner-shutdown"));
                }
                case "cancel-rerequest" -> {
                    current.add(new ExpectedConnection(player, initial, true));
                    accepted.add(new ExpectedWork(initial, 1, "cancellation"));
                    accepted.add(new ExpectedWork(initial, 2, "success"));
                    ready.add(initial);
                }
                case "unauthorized-then-grant" -> {
                    current.add(new ExpectedConnection(player, initial, true));
                    rejected.add(new ExpectedRejection(initial, 1, "UNAUTHORIZED"));
                    accepted.add(new ExpectedWork(initial, 2, "success"));
                    ready.add(initial);
                }
                case "invalid" -> {
                    current.add(new ExpectedConnection(player, null, false));
                    rejected.add(new ExpectedRejection(initial, 0, "INTRINSIC_INVALID"));
                }
                case "normal" -> {
                    current.add(new ExpectedConnection(player, initial, true));
                    accepted.add(new ExpectedWork(initial, 1, "success"));
                    accepted.add(new ExpectedWork(initial, 2, "success"));
                    ready.add(initial);
                }
                default -> throw new AssertionError(role);
            }
        }
        return new ScenarioInput(SEED, SESSION_COUNT, new SettingsInput(2, 4, 0), List.copyOf(sessions),
            List.copyOf(current), List.copyOf(accepted), List.copyOf(rejected), List.copyOf(ready),
            List.of(new OptionalRun(200, "Not run", "future Bukkit characterization"),
                new OptionalRun(300, "Not run", "extreme characterization")));
    }

    private static List<String> actions(String role) {
        return switch (role) {
            case "pressure" -> List.of("join", "activate", "publish", "accept-1..4", "reject-hard-overflow-5",
                "release-pressure", "drain", "close");
            case "production-failure" -> List.of("join", "activate", "publish", "accept-1", "fail-post-build", "close");
            case "replace" -> List.of("join-g1", "activate-g1", "publish-g1", "accept-g1-1", "replace-g2",
                "reject-stale-g1-effect", "activate-g2", "publish-g2", "accept-g2-1..2", "drain", "close");
            case "leave" -> List.of("join", "activate", "publish", "accept-1..2", "leave", "reject-late-effect");
            case "cancel-rerequest" ->
                List.of("join", "activate", "publish", "accept-1", "cancel-1", "accept-2", "drain", "close");
            case "unauthorized-then-grant" ->
                List.of("join", "activate", "publish", "reject-unauthorized-1", "grant", "accept-2", "drain", "close");
            case "invalid" -> List.of("join", "activate", "publish", "reject-intrinsic-invalid-0", "close-session");
            case "normal" -> List.of("join", "activate", "publish", "accept-1..2", "drain", "close");
            default -> throw new AssertionError(role);
        };
    }

    private static String connection(int index, int generation) {
        return "player-%03d-g%d".formatted(index, generation);
    }

    public record ScenarioInput(long seed, int sessionCount, SettingsInput settings, List<SessionPlan> sessions,
        List<ExpectedConnection> expectedCurrent, List<ExpectedWork> acceptedWork, List<ExpectedRejection> rejectedWork,
        List<String> readySurvivors, List<OptionalRun> optionalRuns) {
    }

    public record SettingsInput(int softLimit, int hardLimit, long bytesPerSecond) {
    }

    public record SessionPlan(int index, String playerId, String initialConnection, String replacementConnection,
        String role, List<String> actions) {
    }

    public record ExpectedConnection(String playerId, String connectionId, boolean current) {
    }

    public record ExpectedWork(String connectionId, long transferId, String terminal) {
    }

    public record ExpectedRejection(String connectionId, long transferId, String reason) {
    }

    public record OptionalRun(int sessions, String outcome, String purpose) {
    }
}
