package com.elfmcys.ysm.mock.supervisor;

import com.elfmcys.ysm.mock.evidence.MockExact100Input;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Compares the serialized exact-100 plan with append-only endpoint observations. */
final class MockExact100Verifier {
    private static final String REJECTED = "rejected:";

    private MockExact100Verifier() {}

    static void verify(JsonObject input, List<Observation> client, List<Observation> server) throws IOException {
        var plan = MockExact100Input.parse(input);
        require(plan.playerCount() == 100 && plan.players().size() == 100,
            "Exact-100 plan must contain exactly 100 logical players");

        var expectedRequests = new LinkedHashMap<String, String>();
        var expectedAccepted = new LinkedHashSet<String>();
        var expectedTerminals = new LinkedHashMap<String, String>();
        var expectedRejections = new LinkedHashMap<String, String>();
        var expectedServerState = new LinkedHashMap<String, ConnectionState>();
        var expectedClientState = new LinkedHashMap<String, ConnectionState>();
        var expectedActivations = new LinkedHashMap<String, List<String>>();
        var expectedPublications = new LinkedHashSet<String>();
        var players = new LinkedHashSet<String>();
        for (var player : plan.players()) {
            require(players.add(player.playerId()), "Exact-100 plan repeated player " + player.playerId());
            require(player.connections() != null && !player.connections().isEmpty(),
                "Exact-100 player omitted its connection plan: " + player.playerId());
            for (var connection : player.connections()) {
                var connectionId = connection.connectionId();
                require(
                    expectedServerState.putIfAbsent(connectionId,
                        new ConnectionState(connection.current(), false)) == null,
                    "Exact-100 plan repeated connection " + connectionId);
                expectedClientState.put(connectionId, new ConnectionState(connection.current(), connection.ready()));
                expectedActivations.put(connectionId, List.copyOf(connection.activationTerminals()));
                expectedPublications.add(connectionId);
                require(connection.actions() != null && !connection.actions().isEmpty(),
                    "Exact-100 connection omitted its action stream: " + connectionId);
                for (var action : connection.actions()) {
                    var operation = operation(connectionId, action.transferId());
                    require(expectedRequests.putIfAbsent(operation, action.request()) == null,
                        "Exact-100 plan repeated transfer action " + operation);
                    if (action.disposition().startsWith(REJECTED)) {
                        expectedRejections.put(operation, action.disposition().substring(REJECTED.length()));
                    } else {
                        expectedAccepted.add(operation);
                        expectedTerminals.put(operation, action.disposition());
                    }
                }
            }
        }
        require(expectedServerState.size() == 101, "Exact-100 plan must contain 101 exact connection generations");

        var observedRequests = new LinkedHashMap<String, String>();
        for (var event : client) {
            if (!event.eventKind().equals("typed-frame-send")) {
                continue;
            }
            var messageType = event.payload().get("messageType");
            final String request;
            if (messageType != null && messageType.endsWith(".MetadataPrefixRequestProto")) {
                request = "metadata";
            } else if (messageType != null && messageType.endsWith(".ModelChunkRequestProto")) {
                request = "chunk";
            } else {
                continue;
            }
            putUnique(observedRequests, operation(event.connectionId(), transferId(event.actionId())), request,
                "client request");
        }

        var observedAccepted = new LinkedHashSet<String>();
        var observedTerminals = new LinkedHashMap<String, String>();
        var observedRejections = new LinkedHashMap<String, String>();
        for (var event : server) {
            var transferId = event.payload().get("transferId");
            if (transferId == null) {
                continue;
            }
            var operation = operation(event.connectionId(), Long.parseUnsignedLong(transferId));
            switch (event.eventKind()) {
                case "server-transfer-accepted" ->
                    require(observedAccepted.add(operation), "Server observed duplicate admission " + operation);
                case "server-transfer-terminal" ->
                    putUnique(observedTerminals, operation, event.payload().get("classification"), "server terminal");
                case "server-transfer-rejected" ->
                    putUnique(observedRejections, operation, event.payload().get("reason"), "server rejection");
                default -> {
                }
            }
        }

        var serverState = states(server, "server-connection-state", false);
        var clientState = states(client, "client-connection-state", true);
        var publications = uniqueConnections(client, "client-publication");
        var activations = activationTerminals(client);

        require(observedRequests.equals(expectedRequests),
            "Client request stream differs from the serialized exact-100 actions");
        require(observedAccepted.equals(expectedAccepted),
            "Server accepted-work set differs from the serialized exact-100 actions");
        require(observedTerminals.equals(expectedTerminals),
            "Server terminal ledger differs from the serialized exact-100 actions");
        require(observedRejections.equals(expectedRejections),
            "Server rejection ledger differs from the serialized exact-100 actions");
        require(serverState.equals(expectedServerState),
            "Server current-connection ledger differs from the serialized exact-100 plan");
        require(clientState.equals(expectedClientState),
            "Client current/ready ledger differs from the serialized exact-100 plan");
        require(publications.equals(expectedPublications),
            "Client publication ledger differs from the serialized exact-100 plan");
        require(activations.equals(expectedActivations),
            "Client activation terminal ledger differs from the serialized exact-100 plan");
        require(expectedTerminals.keySet().equals(expectedAccepted),
            "Serialized accepted work omitted a terminal expectation");
    }

    private static Map<String, ConnectionState> states(List<Observation> events, String eventKind, boolean includeReady)
        throws IOException {
        var states = new LinkedHashMap<String, ConnectionState>();
        for (var event : events) {
            if (!event.eventKind().equals(eventKind)) {
                continue;
            }
            var current = event.payload().get("current");
            require("true".equals(current) || "false".equals(current),
                eventKind + " omitted current state for " + event.connectionId());
            var ready = event.payload().get("ready");
            if (includeReady) {
                require("true".equals(ready) || "false".equals(ready),
                    eventKind + " omitted ready state for " + event.connectionId());
            }
            var state = new ConnectionState(Boolean.parseBoolean(current), includeReady && Boolean.parseBoolean(ready));
            putUnique(states, event.connectionId(), state, eventKind);
        }
        return states;
    }

    private static LinkedHashSet<String> uniqueConnections(List<Observation> events, String eventKind)
        throws IOException {
        var result = new LinkedHashSet<String>();
        for (var event : events) {
            if (event.eventKind().equals(eventKind)) {
                require(result.add(event.connectionId()), "Duplicate " + eventKind + " for " + event.connectionId());
            }
        }
        return result;
    }

    private static Map<String, List<String>> activationTerminals(List<Observation> events) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var event : events) {
            if (event.eventKind().equals("client-activation-terminal")) {
                result.computeIfAbsent(event.connectionId(), ignored -> new ArrayList<>())
                    .add(event.payload().get("classification"));
            }
        }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return result;
    }

    private static long transferId(String actionId) throws IOException {
        var separator = actionId.lastIndexOf('-');
        require(separator >= 0 && separator + 1 < actionId.length(),
            "Typed request omitted its transfer id: " + actionId);
        try {
            return Long.parseUnsignedLong(actionId.substring(separator + 1));
        } catch (NumberFormatException failure) {
            throw new IOException("Typed request has an invalid transfer id: " + actionId, failure);
        }
    }

    private static String operation(String connectionId, long transferId) {
        return connectionId + "/transfer-" + Long.toUnsignedString(transferId);
    }

    private static <T> void putUnique(Map<String, T> target, String key, T value, String description)
        throws IOException {
        require(value != null, description + " omitted its value for " + key);
        require(target.putIfAbsent(key, value) == null, "Duplicate " + description + " for " + key);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    record Observation(String connectionId, String actionId, String eventKind, Map<String, String> payload) {
    }

    private record ConnectionState(boolean current, boolean ready) {
    }
}
