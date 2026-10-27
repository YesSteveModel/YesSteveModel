package com.elfmcys.ysm.mock.supervisor;

import com.elfmcys.ysm.mock.evidence.MockExact100Input;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MockExact100VerifierTest {
    @Test
    void completeSerializedPlanMatchesItsObservations() {
        var input = input();
        var observations = observations(input);
        assertDoesNotThrow(() -> MockExact100Verifier.verify(input, observations.client(), observations.server()));
    }

    @Test
    void deletingOneSerializedActionFailsClosed() {
        var input = input();
        var observations = observations(input);
        var mutated = input.deepCopy();
        mutated.getAsJsonObject(MockExact100Input.KEY).getAsJsonArray("players").get(0).getAsJsonObject()
            .getAsJsonArray("connections").get(0).getAsJsonObject().getAsJsonArray("actions").remove(0);
        assertThrows(IOException.class,
            () -> MockExact100Verifier.verify(mutated, observations.client(), observations.server()));
    }

    @Test
    void deletingOneObservedTerminalFailsClosed() {
        var input = input();
        var observations = observations(input);
        var server = new ArrayList<>(observations.server());
        server.remove(server.stream().filter(event -> event.eventKind().equals("server-transfer-terminal")).findFirst()
            .orElseThrow());
        assertThrows(IOException.class, () -> MockExact100Verifier.verify(input, observations.client(), server));
    }

    private static JsonObject input() {
        var bytes = EvidenceJson.canonicalBytes(Map.of(MockExact100Input.KEY, MockExact100Input.create()));
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Observations observations(JsonObject input) {
        var client = new ArrayList<MockExact100Verifier.Observation>();
        var server = new ArrayList<MockExact100Verifier.Observation>();
        var plan = MockExact100Input.parse(input);
        for (var player : plan.players()) {
            for (var connection : player.connections()) {
                var connectionId = connection.connectionId();
                client.add(event(connectionId, "publication-full", "client-publication", Map.of("entries", "1")));
                for (var classification : connection.activationTerminals()) {
                    client.add(event(connectionId, "activation-terminal", "client-activation-terminal",
                        Map.of("classification", classification)));
                }
                client.add(event(connectionId, "client-connection-state", "client-connection-state", Map.of("current",
                    Boolean.toString(connection.current()), "ready", Boolean.toString(connection.ready()))));
                server.add(event(connectionId, "server-connection-state", "server-connection-state",
                    Map.of("current", Boolean.toString(connection.current()))));
                for (var action : connection.actions()) {
                    var transferId = Long.toUnsignedString(action.transferId());
                    client.add(event(connectionId, action.request() + "-request-" + transferId, "typed-frame-send",
                        Map.of("messageType", action.request().equals("metadata")
                            ? MetadataPrefixRequestProto.class.getName() : ModelChunkRequestProto.class.getName())));
                    if (action.disposition().startsWith("rejected:")) {
                        server.add(event(connectionId, "rejected-" + transferId, "server-transfer-rejected", Map.of(
                            "reason", action.disposition().substring("rejected:".length()), "transferId", transferId)));
                    } else {
                        server.add(event(connectionId, "accepted-" + transferId, "server-transfer-accepted",
                            Map.of("transferId", transferId)));
                        server.add(event(connectionId, "terminal-" + transferId, "server-transfer-terminal",
                            Map.of("classification", action.disposition(), "transferId", transferId)));
                    }
                }
            }
        }
        return new Observations(List.copyOf(client), List.copyOf(server));
    }

    private static MockExact100Verifier.Observation event(String connectionId, String actionId, String eventKind,
        Map<String, String> payload) {
        return new MockExact100Verifier.Observation(connectionId, actionId, eventKind, payload);
    }

    private record Observations(List<MockExact100Verifier.Observation> client,
        List<MockExact100Verifier.Observation> server) {
    }
}
