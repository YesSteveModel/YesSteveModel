package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.identity.Fingerprint;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Exact unbound input identity; execution declarations and compiler backends do not participate. */
public record AnimationInputIdentity(Hash256 fingerprint, Hash256 containerId, String targetId, ExpressionSet set,
    Fingerprint sourceFingerprint, CompilerOptions sourceOptions) {

    private static final String DOMAIN = "ysm:animation-input:v1:" + AbiVersion.CACHE_ANIMATION;

    public AnimationInputIdentity {
        Objects.requireNonNull(fingerprint);
        Objects.requireNonNull(containerId);
        Objects.requireNonNull(set);
        Objects.requireNonNull(sourceFingerprint);
        Objects.requireNonNull(sourceOptions);
        if (Objects.requireNonNull(targetId).isBlank())
            throw new IllegalArgumentException("Empty animation input target");
    }

    public static AnimationInputIdentity create(Hash256 containerId, String targetId, ExpressionSet set,
        Fingerprint sourceFingerprint, CompilerOptions sourceOptions) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(DOMAIN);
                output.write(containerId.bytes());
                output.writeUTF(targetId);
                output.writeUTF(set.id());
                output.writeUTF(ModelProgramInventory.LOWERING_ABI);
                output.writeUTF(sourceFingerprint.algorithm().stableId());
                output.writeInt(sourceFingerprint.algorithm().abiVersion());
                output.write(sourceFingerprint.value().bytes().toByteArray());
                output.writeBoolean(sourceOptions.divZeroFallback());
            }
            return new AnimationInputIdentity(ModelHashing.blake3(bytes.toByteArray()), containerId, targetId, set,
                sourceFingerprint, sourceOptions);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public String key() {
        return fingerprint.toString();
    }
}
