package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import java.nio.file.Path;
import java.util.Objects;

/** Uses the production input namespace and default sources for a converted model. */
public final class ModelMolangBenchmarkInput implements AutoCloseable {
    private final ManagedContainer content;
    private final ModelResourcePurpose purpose;
    private final ModelDataProto definition;
    private final StringDataProto functions;
    private final SettingsProto settings;
    private final String targetId;
    private final ExpressionSet set;
    private final RenderTargetKind kind;

    public ModelMolangBenchmarkInput(Path container, String purpose) throws Exception {
        this(container, purpose, "player", ExpressionSet.MAIN);
    }

    public ModelMolangBenchmarkInput(Path container, String purpose, String targetId, ExpressionSet set)
        throws Exception {
        this.purpose = ModelResourcePurpose.valueOf(purpose);
        if (this.purpose != ModelResourcePurpose.FULL)
            throw new IllegalArgumentException("AOT compilation benchmarks require FULL");
        this.targetId = targetId;
        this.set = set;
        content = ManagedContainer.openDirect(container,
            new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("benchmark-model")));
        try {
            var target = content.view().requireRenderTarget(targetId);
            kind = target.kind();
            var local = set.select(target.readDefinition(() -> false, content.chunks()));
            var strings = content.view().getCommon().readStringData(() -> false, content.chunks());
            settings = content.view().getMetadata().getSettings();
            var builtinRoot = Path.of(Objects
                .requireNonNull(ModelMolangBenchmarkInput.class.getResource("/assets/ysm/builtin/default")).toURI());
            try (var directory = new Directory(builtinRoot)) {
                var memory = ModelParser.parseBuiltinDefaultMemory(directory);
                var bytes = memory.bytes();
                var builtin = ManagedContainer.openResidentDefault(bytes,
                    new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
                try (var representation = builtin.representation()) {
                    var domain = DefaultAnimationKey.domain(target.descriptor(), set.id());
                    var fallbackTarget = builtin.view().getRenderTargets().stream()
                        .filter(
                            candidate -> DefaultAnimationKey.domain(candidate.descriptor(), set.id()).equals(domain))
                        .findFirst().orElseThrow(() -> new UnavailableDefaultSource(domain));
                    var fallback = new DefaultAnimationRuntime.Source(representation.containerId(), fallbackTarget.id(),
                        set, set.select(fallbackTarget.readDefinition(() -> false, builtin.chunks())),
                        builtin.view().getCommon().readStringData(() -> false, builtin.chunks()));
                    var closure = local.toBuilder();
                    fallback.definition().animationFiles()
                        .forEach((file, value) -> closure.putAnimationFiles(fallback.prefix() + file, value));
                    definition = closure.build();
                    var merge =
                        Class.forName(ModelRenderTargetLoader.class.getName() + "$DefaultBuildStage").getDeclaredMethod(
                            "mergeFunctions", StringDataProto.class, DefaultAnimationRuntime.Source.class);
                    merge.setAccessible(true);
                    functions = (StringDataProto)merge.invoke(null, strings, fallback);
                }
            }
        } catch (Throwable failure) {
            content.representation().close();
            throw failure;
        }
    }

    public AnimationStore compile(RenderTargetExpressionModuleFactory factory) throws Exception {
        var binding = binding(factory);
        return ModelRenderTargetLoader
            .prepareExpressions(factory, () -> false, content.representation().containerId(), targetId, set, binding,
                definition, functions, settings, false, purpose, kind, content.view().getMetadata().expressionOptions())
            .store();
    }

    public com.elfmcys.ysm.expression.host.ExpressionBinding binding(RenderTargetExpressionModuleFactory factory) {
        return kind == RenderTargetKind.PLAYER
            ? factory.expressionBinding(false, net.minecraft.client.player.AbstractClientPlayer.class)
            : factory.expressionBinding(false);
    }

    public ModelDataProto projectedDefinition() {
        return definition;
    }

    public StringDataProto functions() {
        return functions;
    }

    public SettingsProto settings() {
        return settings;
    }

    public com.elfmcys.ysm.model.domain.Hash256 containerId() {
        return content.representation().containerId();
    }

    public static final class UnavailableDefaultSource extends IllegalStateException {
        private UnavailableDefaultSource(String domain) {
            super("Default source is unavailable: " + domain);
        }
    }

    @Override
    public void close() {
        content.representation().close();
    }
}
