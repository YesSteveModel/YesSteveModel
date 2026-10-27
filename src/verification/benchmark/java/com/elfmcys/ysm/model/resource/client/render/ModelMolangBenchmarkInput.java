package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import java.nio.file.Path;
import java.util.Objects;

/** Uses the production purpose projection and default closure for a converted model. */
public final class ModelMolangBenchmarkInput implements AutoCloseable {
    private final ManagedContainer content;
    private final ModelResourcePurpose purpose;
    private final ModelDataProto definition;
    private final StringDataProto functions;
    private final SettingsProto settings;

    public ModelMolangBenchmarkInput(Path container, String purpose) throws Exception {
        this.purpose = ModelResourcePurpose.valueOf(purpose);
        content = ManagedContainer.openDirect(container,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("benchmark-model")));
        try {
            var target = content.view().requireRenderTarget("player");
            var local = ExpressionSet.MAIN.select(target.readDefinition(() -> false, content.chunks()));
            var strings = content.view().getCommon().readStringData(() -> false, content.chunks());
            settings = content.view().getMetadata().getSettings();
            var builtinRoot = Path.of(Objects
                .requireNonNull(ModelMolangBenchmarkInput.class.getResource("/assets/ysm/builtin/default")).toURI());
            try (var directory = new Directory(builtinRoot);
                var memory = ModelParser.parseBuiltinDefaultMemory(directory); var bytes = memory.bytes()) {
                var builtin = ManagedContainer.openResidentDefault(bytes,
                    new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
                try (var representation = builtin.representation()) {
                    var fallback = new DefaultAnimationRuntime.Source(representation.containerId(), "player",
                        ExpressionSet.MAIN,
                        ExpressionSet.MAIN.select(
                            builtin.view().requireRenderTarget("player").readDefinition(() -> false, builtin.chunks())),
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

    public RenderTargetExpressionModule compile(RenderTargetExpressionModuleFactory factory) throws Exception {
        var subject = purpose == ModelResourcePurpose.FULL ? net.minecraft.client.player.AbstractClientPlayer.class
            : CustomGuiPlayerEntity.FakePlayer.class;
        return ModelRenderTargetLoader.prepareExpressions(factory, () -> false, content.representation().containerId(),
            "player", ExpressionSet.MAIN, factory.expressionBinding(false, subject), definition, functions, settings,
            false, purpose, content.view().requireRenderTarget("player").kind()).module();
    }

    @Override
    public void close() {
        content.representation().close();
    }
}
