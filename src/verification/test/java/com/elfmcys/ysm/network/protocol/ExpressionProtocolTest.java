package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExpressionProtocolTest {
    @Test
    void receiverRequiresEverySharedIdentityDimensionAndSchemaTwo() {
        try (var module = TestExpressionModules.create()) {
            assertTrue(ExpressionProtocol.matches(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())),
                module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(
                ExpressionProtocol
                    .matches(
                        ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId()))
                            .toBuilder().setSchemaVersion(1).build(),
                        module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(
                ExpressionProtocol
                    .matches(
                        ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId()))
                            .toBuilder().setSchemaVersion(3).build(),
                        module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(ExpressionProtocol.matches(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())).toBuilder()
                    .setModelId(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(
                ExpressionProtocol
                    .matches(
                        ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId()))
                            .toBuilder().setTargetId("other").build(),
                        module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(ExpressionProtocol.matches(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())).toBuilder()
                    .setModuleIdentity(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(ExpressionProtocol.matches(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())).toBuilder()
                    .setConfigIdentity(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                module.sharedSemantics(TestExpressionModules.consumerId())));
            assertFalse(ExpressionProtocol.matches(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())).toBuilder()
                    .setCatalogIdentity(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                module.sharedSemantics(TestExpressionModules.consumerId())));
        }
    }

    @Test
    void localArtifactAndProgramIdentityDoNotAffectSharedSemantics() {
        try (var sender = TestExpressionModules.createLocalVariant(1, 3);
            var receiver = TestExpressionModules.createLocalVariant(2, 17)) {
            assertNotEquals(sender.identity().fingerprint(), receiver.identity().fingerprint());
            assertEquals(3, sender
                .requireProgram("function/@sync", com.elfmcys.ysm.expression.inventory.ProgramRole.EVENT_SYNC, "sync:0")
                .sourceId());
            assertEquals(17, receiver
                .requireProgram("function/@sync", com.elfmcys.ysm.expression.inventory.ProgramRole.EVENT_SYNC, "sync:0")
                .sourceId());

            var wire = ExpressionProtocol.identity(sender.sharedSemantics(TestExpressionModules.consumerId()));
            assertTrue(ExpressionProtocol.matches(wire, receiver.sharedSemantics(TestExpressionModules.consumerId())));
            assertArrayEquals(
                com.elfmcys.ysm.util.ProtoBytes.copy(ExpressionProtocol
                    .identity(sender.sharedSemantics(TestExpressionModules.consumerId())).moduleIdentity()),
                com.elfmcys.ysm.util.ProtoBytes.copy(ExpressionProtocol
                    .identity(receiver.sharedSemantics(TestExpressionModules.consumerId())).moduleIdentity()));
        }
    }

    @Test
    void serverAuthorityRejectsModelTargetConfigAndCatalogDrift() {
        try (var module = TestExpressionModules.create()) {
            var settings = SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
                .setDisablePreviewRotation(false).build();
            var modelId = TestExpressionModules.consumerId();
            assertTrue(ExpressionProtocol.matchesServerAuthority(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())), modelId,
                "test", settings));
            assertFalse(ExpressionProtocol.matchesServerAuthority(
                ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())), modelId,
                "other", settings));
            assertFalse(
                ExpressionProtocol
                    .matchesServerAuthority(
                        ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId()))
                            .toBuilder().setModuleIdentity(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                        modelId, "test", settings));
            assertFalse(
                ExpressionProtocol
                    .matchesServerAuthority(
                        ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId()))
                            .toBuilder().setConfigIdentity(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                        modelId, "test", settings));
            assertFalse(
                ExpressionProtocol.matchesServerAuthority(
                    ExpressionProtocol.identity(module.sharedSemantics(TestExpressionModules.consumerId())).toBuilder()
                        .setCatalogIdentity(java.nio.ByteBuffer.wrap(new byte[32])).build(),
                    modelId, "test", settings));
            assertFalse(ExpressionProtocol.matchesServerAuthority(ExpressionProtocol
                .identity(module.sharedSemantics(TestExpressionModules.consumerId())).toBuilder().setSchemaVersion(1)
                .setModuleIdentity(
                    java.nio.ByteBuffer.wrap(module.identity().fingerprint().value().bytes().toByteArray()))
                .build(), modelId, "test", settings));
        }
    }
}
