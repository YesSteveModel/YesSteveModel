package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
import java.lang.annotation.ElementType;
import java.util.*;

/** Called by the client startup owner before it constructs any target factory. */
public final class MolangExtensionLoader {
    private MolangExtensionLoader() {}

    public static ExpressionHost createHost(HostAvailability availability) {
        var mods = net.minecraftforge.fml.ModList.get();
        var admitted = com.elfmcys.ysm.api.internal.MolangDeclarationBootstrap.freeze(
            Objects.requireNonNull(availability, "availability"),
            discover(mods.getModFiles(), MolangExtensionLoader.class.getClassLoader()),
            new MolangExtensions.Qualification() {
                public boolean dependency(String id, String range) {
                    var mod = mods.getModContainerById(id);
                    return mod.isPresent() && (range.isEmpty()
                        || MolangExtensions.versionMatches(mod.get().getModInfo().getVersion().toString(), range));
                }

                public boolean checker(MolangExtensions.Source source,
                    com.elfmcys.ysm.api.molang.DeclarationSchema schema) {
                    if (schema.builtin())
                        return schema.dependencies().stream()
                            .allMatch(dependency -> java.util.Arrays.stream(OptionalCapability.values())
                                .filter(capability -> capability.modId().equals(dependency.modId()))
                                .allMatch(availability::supports));
                    if (schema.manual())
                        return true;
                    try {
                        var result = com.elfmcys.ysm.api.internal.CompatibilityQualification.check(schema.owner(),
                            source.loader());
                        result.issues().forEach(issue -> YesSteveModel.LOGGER.warn("Molang checker: {}", issue));
                        return result.compatible();
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        throw new IllegalArgumentException("cannot qualify generated checker", failure);
                    }
                }
            });
        admitted.diagnostics()
            .forEach(message -> YesSteveModel.LOGGER.error("Molang extension admission: {}", message));
        return admitted.host();
    }

    public static List<MolangExtensions.Source>
        discover(List<? extends net.minecraftforge.forgespi.language.IModFileInfo> files, ClassLoader loader) {
        var sources = new ArrayList<MolangExtensions.Source>();
        for (var fileInfo : files) {
            var file = fileInfo.getFile();
            var refs = file.getScanResult().getAnnotations().stream().filter(a -> a.targetType() == ElementType.TYPE)
                .filter(a -> a.annotationType().getClassName().equals(YsmEntityComponent.class.getName()))
                .map(a -> new MolangExtensions.DeclarationRef(a.clazz().getClassName(),
                    String.valueOf(a.annotationData().get("value")),
                    String.valueOf(a.annotationData().getOrDefault("modId", ""))))
                .sorted(Comparator.comparing(MolangExtensions.DeclarationRef::className)).toList();
            var owners = new TreeSet<String>();
            fileInfo.getMods().forEach(mod -> owners.add(mod.getModId()));
            sources.add(
                new MolangExtensions.Source(file.getFilePath().toString(), owners, refs, loader, file.getFilePath()));
        }
        return List.copyOf(sources);
    }
}
