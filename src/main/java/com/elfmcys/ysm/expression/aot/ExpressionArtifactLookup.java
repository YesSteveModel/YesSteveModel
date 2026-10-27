package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.compatibility.AbiVersion;

import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.HostLinkage;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import dev.semaweave.compilation.ExpectedArtifactIdentity;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.compilation.BackendDescriptor;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.DefaultExpressionAotCompiler;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Trusted set input identity, computed before accepting any cached artifact metadata. */
public record ExpressionArtifactLookup(Fingerprint fingerprint, Hash256 containerId, String targetId, ExpressionSet set,
    ExpectedArtifactIdentity expected, ExpressionBinding expressionBinding, Fingerprint configFingerprint,
    Fingerprint eventCatalogFingerprint, Fingerprint productionLinkageFingerprint, ModelResourcePurpose purpose) {

    private static final String DOMAIN = "ysm:expression-artifact-lookup:" + AbiVersion.CACHE_ANIMATION;

    public ExpressionArtifactLookup {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(expected, "expected");
        if (Objects.requireNonNull(targetId, "targetId").isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        Objects.requireNonNull(expressionBinding, "expressionBinding");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(configFingerprint, "configFingerprint");
        Objects.requireNonNull(eventCatalogFingerprint, "eventCatalogFingerprint");
        Objects.requireNonNull(productionLinkageFingerprint, "productionLinkageFingerprint");
        if (!fingerprint.algorithm().equals(ExpressionIdentityHash.SHA_256.descriptor())
            || !configFingerprint.algorithm().equals(ExpressionIdentityHash.SHA_256.descriptor())
            || !eventCatalogFingerprint.algorithm().equals(ExpressionIdentityHash.SHA_256.descriptor())
            || !productionLinkageFingerprint.algorithm().equals(ExpressionIdentityHash.SHA_256.descriptor())) {
            throw new IllegalArgumentException("expression lookup uses a different hash algorithm");
        }
    }

    public static ExpressionArtifactLookup create(ModelFileIdentity model, String targetId, String selectedTexture,
        BakeProfile bakeProfile, boolean defaultModel, ExpressionBinding expressionBinding, ModelDataProto definition,
        StringDataProto strings, SettingsProto settings, byte[] configBytes, ExpressionHost host,
        MolangEventCatalog eventCatalog, HostLinkage productionLinkage, CompilerOptions options,
        BackendDescriptor backend) {
        return create(model.containerId(), targetId, ExpressionSet.MAIN, expressionBinding, definition, strings,
            settings, configBytes, host, eventCatalog, productionLinkage, options, backend);
    }

    public static ExpressionArtifactLookup create(Hash256 containerId, String targetId, ExpressionSet set,
        ExpressionBinding expressionBinding, ModelDataProto definition, StringDataProto strings, SettingsProto settings,
        byte[] configBytes, ExpressionHost host, MolangEventCatalog eventCatalog, HostLinkage productionLinkage,
        CompilerOptions options, BackendDescriptor backend) {
        try {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(settings, "settings");
            return create(containerId, targetId, set, expressionBinding,
                ModelProgramInventory.sourceFingerprint(definition, strings, settings), configBytes, host, eventCatalog,
                productionLinkage, options, backend, ModelResourcePurpose.FULL);
        } catch (IOException failure) {
            throw new IllegalArgumentException("failed to fingerprint expression sources", failure);
        }
    }

    static ExpressionArtifactLookup create(Hash256 containerId, String targetId, ExpressionSet set,
        ExpressionBinding expressionBinding, Fingerprint sourceFingerprint, byte[] configBytes, ExpressionHost host,
        MolangEventCatalog eventCatalog, HostLinkage productionLinkage, CompilerOptions options,
        BackendDescriptor backend, ModelResourcePurpose purpose) {
        Objects.requireNonNull(configBytes, "configBytes");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(expressionBinding, "expressionBinding");
        Objects.requireNonNull(eventCatalog, "eventCatalog");
        Objects.requireNonNull(productionLinkage, "productionLinkage");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        Objects.requireNonNull(purpose, "purpose");
        var configFingerprint = Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(configBytes));
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                text(output, DOMAIN);
                output.writeUTF(AbiVersion.CACHE_ANIMATION);
                text(output, ExpressionIdentityHash.SHA_256.descriptor().stableId());
                output.writeInt(ExpressionIdentityHash.SHA_256.descriptor().abiVersion());
                field(output, containerId.bytes());
                text(output, targetId);
                text(output, set.id());
                text(output, purpose.ruleId());
                text(output, ModelProgramInventory.LOWERING_ABI);
                field(output, expressionBinding.environment().fingerprint().value().bytes().toByteArray());
                field(output, expressionBinding.fingerprint().value().bytes().toByteArray());
                field(output, configFingerprint.value().bytes().toByteArray());
                field(output, eventCatalog.fingerprint().value().bytes().toByteArray());
                output.writeInt(productionLinkage.abiVersion());
                field(output, productionLinkage.fingerprint().value().bytes().toByteArray());
                text(output, sourceFingerprint.algorithm().stableId());
                output.writeInt(sourceFingerprint.algorithm().abiVersion());
                field(output, sourceFingerprint.value().bytes().toByteArray());
                text(output, host.compilationPolicy().toString());
                text(output, options.toString());
                text(output, host.hostAbi().ownerId());
                text(output, host.hostAbi().version());
                field(output, host.hostAbi().fingerprint().value().bytes().toByteArray());
                var context = host.contextType().descriptor();
                text(output, context.stableId());
                output.writeInt(context.abiVersion());
                text(output, context.carrierBinaryName());
                text(output, context.childAdapterId());
                output.writeInt(context.childAdapterAbi());
                field(output, context.fingerprint().value().bytes().toByteArray());
                field(output, host.registry().fingerprint().value().bytes().toByteArray());
                text(output, backend.stableId());
                text(output, backend.implementationVersion());
                output.writeInt(backend.abiVersion());
                output.writeInt(backend.canonicalOptions().size());
                for (var entry : backend.canonicalOptions().entrySet()) {
                    text(output, entry.getKey());
                    text(output, entry.getValue());
                }
                text(output, DefaultExpressionAotCompiler.PROGRAM_SEMANTIC_ABI);
                text(output, DefaultExpressionAotCompiler.RUNTIME_ABI);
                text(output, DefaultExpressionAotCompiler.CODEGEN_ABI);
                text(output, DefaultExpressionAotCompiler.SOURCE_GENERATOR_ID);
                text(output, DefaultExpressionAotCompiler.SOURCE_GENERATOR_VERSION);
                output.writeInt(17);
                output.writeInt(61);
            }
            var fingerprint =
                Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray()));
            var expected = ExpectedArtifactIdentity.fromVerifiedSource(fingerprint,
                host.compilationPolicy().compilerLimits(), host.hostAbi(), host.registry(), options,
                expressionBinding.bindingPlan(), backend, ExpressionIdentityHash.SHA_256);
            return new ExpressionArtifactLookup(fingerprint, containerId, targetId, set, expected, expressionBinding,
                configFingerprint, eventCatalog.fingerprint(), productionLinkage.fingerprint(), purpose);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public String key() {
        return fingerprint.value().toString();
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        field(output, Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static void field(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
