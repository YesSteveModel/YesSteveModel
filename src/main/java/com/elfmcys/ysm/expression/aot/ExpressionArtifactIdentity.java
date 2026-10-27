package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.compatibility.AbiVersion;

import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import dev.semaweave.compilation.ExpectedArtifactIdentity;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact set content identity; consumer provenance belongs to the render target. */
public record ExpressionArtifactIdentity(ExpectedArtifactIdentity expected, Fingerprint fingerprint,
    Fingerprint lookupFingerprint, Hash256 containerId, String targetId, ExpressionSet set,
    ExpressionBinding expressionBinding, Fingerprint configFingerprint, Fingerprint eventCatalogFingerprint,
    Fingerprint productionLinkageFingerprint, ModelResourcePurpose purpose) {

    private static final String DOMAIN = "ysm:expression-artifact:" + AbiVersion.CACHE_ANIMATION;

    public ExpressionArtifactIdentity {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(lookupFingerprint, "lookupFingerprint");
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(set, "set");
        if (Objects.requireNonNull(targetId, "targetId").isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        Objects.requireNonNull(expressionBinding, "expressionBinding");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(configFingerprint, "configFingerprint");
        Objects.requireNonNull(eventCatalogFingerprint, "eventCatalogFingerprint");
        Objects.requireNonNull(productionLinkageFingerprint, "productionLinkageFingerprint");
        var descriptor = ExpressionIdentityHash.SHA_256.descriptor();
        if (!fingerprint.algorithm().equals(descriptor) || !lookupFingerprint.algorithm().equals(descriptor)
            || !configFingerprint.algorithm().equals(descriptor)
            || !eventCatalogFingerprint.algorithm().equals(descriptor)
            || !productionLinkageFingerprint.algorithm().equals(descriptor)
            || !expected.identity().hashAlgorithm().equals(descriptor)) {
            throw new IllegalArgumentException("expression identity uses a different hash algorithm");
        }
    }

    public static ExpressionArtifactIdentity create(ExpectedArtifactIdentity expected, ModelFileIdentity model,
        String targetId, String selectedTexture, BakeProfile bakeProfile, boolean defaultModel, byte[] configBytes) {
        return create(expected, model, targetId, selectedTexture, bakeProfile,
            ExpressionBinding.standalone(expected.bindingPlan()), defaultModel, configBytes);
    }

    public static ExpressionArtifactIdentity create(ExpectedArtifactIdentity expected, ModelFileIdentity model,
        String targetId, String selectedTexture, BakeProfile bakeProfile, ExpressionBinding expressionBinding,
        boolean defaultModel, byte[] configBytes) {
        Objects.requireNonNull(configBytes, "configBytes");
        var configFingerprint = Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(configBytes));
        var standaloneLinkage = expected.identity().input().hostAbi().fingerprint();
        return create(expected, null, model.containerId(), targetId, ExpressionSet.MAIN, expressionBinding,
            configFingerprint, MolangEventCatalog.production().fingerprint(), standaloneLinkage, ModelResourcePurpose.FULL);
    }

    public static ExpressionArtifactIdentity create(ExpectedArtifactIdentity expected,
        ExpressionArtifactLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return create(expected, lookup.fingerprint(), lookup.containerId(), lookup.targetId(), lookup.set(),
            lookup.expressionBinding(), lookup.configFingerprint(), lookup.eventCatalogFingerprint(),
            lookup.productionLinkageFingerprint(), lookup.purpose());
    }

    private static ExpressionArtifactIdentity create(ExpectedArtifactIdentity expected, Fingerprint lookupFingerprint,
        Hash256 containerId, String targetId, ExpressionSet set, ExpressionBinding expressionBinding,
        Fingerprint configFingerprint, Fingerprint eventCatalogFingerprint, Fingerprint productionLinkageFingerprint,
        ModelResourcePurpose purpose) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                field(output, DOMAIN);
                output.writeUTF(AbiVersion.CACHE_ANIMATION);
                field(output, ExpressionIdentityHash.SHA_256.descriptor().stableId());
                output.writeInt(ExpressionIdentityHash.SHA_256.descriptor().abiVersion());
                field(output, containerId.bytes());
                field(output, targetId);
                field(output, set.id());
                field(output, purpose.ruleId());
                field(output, expressionBinding.fingerprint().value().bytes().toByteArray());
                field(output, configFingerprint.value().bytes().toByteArray());
                field(output, eventCatalogFingerprint.value().bytes().toByteArray());
                field(output, productionLinkageFingerprint.value().bytes().toByteArray());
                var semaweave = expected.identity().artifactFingerprint();
                field(output, semaweave.algorithm().stableId());
                output.writeInt(semaweave.algorithm().abiVersion());
                field(output, semaweave.value().bytes().toByteArray());
            }
            var fingerprint =
                Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray()));
            return new ExpressionArtifactIdentity(expected, fingerprint,
                lookupFingerprint == null ? fingerprint : lookupFingerprint, containerId, targetId, set,
                expressionBinding, configFingerprint, eventCatalogFingerprint, productionLinkageFingerprint, purpose);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public String cacheKey() {
        return fingerprint.value().toString();
    }

    public String lookupKey() {
        return lookupFingerprint.value().toString();
    }

    private static void field(DataOutputStream output, String value) throws IOException {
        field(output, Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static void field(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
