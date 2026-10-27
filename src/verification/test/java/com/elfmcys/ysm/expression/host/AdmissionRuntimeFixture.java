package com.elfmcys.ysm.expression.host;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Plain-JVM checker environment, not evidence of an actual Forge client. */
public final class AdmissionRuntimeFixture {
    private AdmissionRuntimeFixture() {}

    public static Path classes(Path directory) throws Exception {
        Path source = Files.createDirectories(directory.resolve("sources"));
        Path output = Files.createDirectories(directory.resolve("classes"));
        var definitions = java.util.Map.of("net/minecraftforge/fml/loading/FMLLoader",
            "package net.minecraftforge.fml.loading; public final class FMLLoader {public static boolean isProduction(){return false;}}",
            "net/minecraftforge/fml/loading/FMLEnvironment",
            "package net.minecraftforge.fml.loading; public final class FMLEnvironment {public static net.minecraftforge.api.distmarker.Dist dist=net.minecraftforge.api.distmarker.Dist.CLIENT;}",
            "net/minecraftforge/fml/ModList",
            "package net.minecraftforge.fml; public final class ModList {public static ModList get(){return new ModList();}public boolean isLoaded(String id){return true;}}",
            "com/elfmcys/ysm/YesSteveModel",
            "package com.elfmcys.ysm; public final class YesSteveModel {public static final org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger(\"ysm-fixture\");public static boolean isAvailable(){return true;}}");
        var arguments = new java.util.ArrayList<String>(
            java.util.List.of("--release", "17", "-proc:none", "-d", output.toString()));
        for (var entry : definitions.entrySet()) {
            if (entry.getValue().isEmpty())
                continue;
            Path file = source.resolve(entry.getKey() + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            arguments.add(file.toString());
        }
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)) != 0)
            throw new AssertionError("checker fixture compilation failed");
        return output;
    }
}
