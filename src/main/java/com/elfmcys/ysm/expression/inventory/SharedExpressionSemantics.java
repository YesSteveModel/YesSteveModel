package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.model.domain.Hash256;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable cross-client projection of one model target's callable semantics. */
public final class SharedExpressionSemantics {
    private static final String DOMAIN = "ysm:shared-expression-semantics:v1";

    private final Hash256 modelId;
    private final String targetId;
    private final Fingerprint configFingerprint;
    private final Fingerprint eventContractFingerprint;
    private final Fingerprint fingerprint;

    private SharedExpressionSemantics(Hash256 modelId, String targetId, Fingerprint configFingerprint,
        Fingerprint eventContractFingerprint, Fingerprint fingerprint) {
        this.modelId = modelId;
        this.targetId = targetId;
        this.configFingerprint = configFingerprint;
        this.eventContractFingerprint = eventContractFingerprint;
        this.fingerprint = fingerprint;
    }

    public static SharedExpressionSemantics create(Hash256 modelId, String targetId, Fingerprint configFingerprint,
        Fingerprint eventContractFingerprint) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(configFingerprint, "configFingerprint");
        Objects.requireNonNull(eventContractFingerprint, "eventContractFingerprint");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        var descriptor = ExpressionIdentityHash.SHA_256.descriptor();
        if (!configFingerprint.algorithm().equals(descriptor)
            || !eventContractFingerprint.algorithm().equals(descriptor)) {
            throw new IllegalArgumentException("shared semantics uses a different hash algorithm");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                field(output, DOMAIN);
                output.writeInt(1);
                field(output, ExpressionIdentityHash.SHA_256.descriptor().stableId());
                output.writeInt(ExpressionIdentityHash.SHA_256.descriptor().abiVersion());
                field(output, modelId.bytes());
                field(output, targetId);
                field(output, configFingerprint.value().bytes().toByteArray());
                field(output, eventContractFingerprint.value().bytes().toByteArray());
            }
            var fingerprint =
                Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray()));
            return new SharedExpressionSemantics(modelId, targetId, configFingerprint, eventContractFingerprint,
                fingerprint);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public Hash256 modelId() {
        return modelId;
    }

    public String targetId() {
        return targetId;
    }

    public Fingerprint configFingerprint() {
        return configFingerprint;
    }

    public Fingerprint eventContractFingerprint() {
        return eventContractFingerprint;
    }

    public Fingerprint fingerprint() {
        return fingerprint;
    }

    private static void field(DataOutputStream output, String value) throws IOException {
        field(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void field(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
