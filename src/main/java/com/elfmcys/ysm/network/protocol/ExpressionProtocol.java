package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.network.ExpressionTargetIdentityProto;
import com.elfmcys.ysm.util.ProtoBytes;

import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Structural and exact-identity checks shared by expression wire endpoints. */
public final class ExpressionProtocol {
    public static final int SCHEMA_VERSION = 2;
    public static final int IDENTITY_BYTES = 32;
    public static final int MAX_TARGET_ID_LENGTH = 128;
    public static final int MAX_BUTTON_ID_LENGTH = 128;
    public static final int MAX_LABEL_NAME_LENGTH = 128;

    private ExpressionProtocol() {}

    public static ExpressionTargetIdentityProto identity(SharedExpressionSemantics identity) {
        Objects.requireNonNull(identity, "identity");
        return ExpressionTargetIdentityProto.newBuilder().setSchemaVersion(SCHEMA_VERSION)
            .setModelId(ByteBuffer.wrap(identity.modelId().bytes())).setTargetId(identity.targetId())
            .setModuleIdentity(ByteBuffer.wrap(bytes(identity.fingerprint())))
            .setConfigIdentity(ByteBuffer.wrap(bytes(identity.configFingerprint())))
            .setCatalogIdentity(ByteBuffer.wrap(bytes(identity.eventContractFingerprint()))).build();
    }

    public static boolean matches(ExpressionTargetIdentityProto wire, SharedExpressionSemantics semantics) {
        if (!validIdentity(wire)) {
            return false;
        }
        Objects.requireNonNull(semantics, "semantics");
        return ProtoBytes.equals(semantics.modelId(), wire.modelId()) && semantics.targetId().equals(wire.targetId())
            && matches(wire.moduleIdentity(), semantics.fingerprint())
            && matches(wire.configIdentity(), semantics.configFingerprint())
            && matches(wire.catalogIdentity(), semantics.eventContractFingerprint());
    }

    public static boolean validIdentity(ExpressionTargetIdentityProto wire) {
        return wire != null && wire.schemaVersion() == SCHEMA_VERSION && wire.modelId().remaining() == Hash256.SIZE
            && !wire.targetId().isBlank() && wire.targetId().length() <= MAX_TARGET_ID_LENGTH
            && wire.moduleIdentity().remaining() == IDENTITY_BYTES
            && wire.configIdentity().remaining() == IDENTITY_BYTES
            && wire.catalogIdentity().remaining() == IDENTITY_BYTES;
    }

    public static boolean matchesServerAuthority(ExpressionTargetIdentityProto wire, Hash256 modelId, String targetId,
        SettingsProto settings) {
        if (!validIdentity(wire) || !ProtoBytes.equals(Objects.requireNonNull(modelId, "modelId"), wire.modelId())
            || !Objects.requireNonNull(targetId, "targetId").equals(wire.targetId())) {
            return false;
        }
        try {
            var config = Fingerprint.compute(ExpressionIdentityHash.SHA_256,
                ReadOnlyBytes.copyOf(ProtoUtil.serializeToArray(Objects.requireNonNull(settings, "settings"))));
            var semantics = SharedExpressionSemantics.create(modelId, targetId, config,
                MolangEventCatalog.production().fingerprint());
            return matches(wire, semantics);
        } catch (java.io.IOException invalidSettings) {
            return false;
        }
    }

    private static boolean matches(ByteBuffer wire, Fingerprint expected) {
        var value = bytes(expected);
        if (wire.remaining() != value.length) {
            return false;
        }
        var source = wire.duplicate();
        for (var index = 0; index < value.length; index++) {
            if (source.get() != value[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] bytes(Fingerprint fingerprint) {
        return fingerprint.value().bytes().toByteArray();
    }
}
