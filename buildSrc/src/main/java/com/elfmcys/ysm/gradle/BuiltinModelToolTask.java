package com.elfmcys.ysm.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/** Runs the builtin validation tool in an isolated JVM with a complete cache contract. */
@CacheableTask
public abstract class BuiltinModelToolTask extends DefaultTask {
    public static final String GENERATE_DEFAULT_CONTRACT = "generate-default-contract";
    public static final String GENERATE_INDEX = "generate-index";
    public static final String VERIFY = "verify";

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @Input
    public abstract Property<String> getCommand();

    @Classpath
    public abstract ConfigurableFileCollection getToolClasspath();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getNativeLibrary();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDefaultContractFile();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getIndexFile();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAnimationHistoryFile();

    @Input
    public abstract Property<String> getOperatingSystem();

    @Input
    public abstract Property<String> getArchitecture();

    @Input
    public abstract Property<String> getJavaRuntimeVersion();

    @Input
    public abstract Property<String> getJavaVendor();

    @Internal
    public abstract Property<String> getJavaExecutable();

    @OutputFile
    public abstract RegularFileProperty getResultFile();

    @LocalState
    public abstract DirectoryProperty getWorkDirectory();

    @TaskAction
    public void runTool() throws IOException {
        var output = getResultFile().get().getAsFile().toPath();
        var workDirectory = getWorkDirectory().get().getAsFile().toPath();
        Files.deleteIfExists(output);
        getFileSystemOperations().delete(spec -> spec.delete(workDirectory));
        Files.createDirectories(workDirectory);

        var arguments = arguments(output, workDirectory);
        getExecOperations().javaexec(spec -> {
            spec.setClasspath(getToolClasspath());
            spec.getMainClass().set("com.elfmcys.ysm.tool.BuiltinModelIndexTool");
            spec.setExecutable(getJavaExecutable().get());
            spec.args(arguments);
        }).assertNormalExitValue();

        if (!Files.isRegularFile(output)) {
            throw new GradleException("Builtin model tool did not produce its declared output: " + output);
        }
    }

    private List<String> arguments(Path output, Path workDirectory) {
        var arguments = new ArrayList<String>();
        var command = getCommand().get();
        arguments.add(command);
        arguments.add(getNativeLibrary().get().getAsFile().getAbsolutePath());
        arguments.add(getSourceRoot().get().getAsFile().getAbsolutePath());
        arguments.add(output.toAbsolutePath().normalize().toString());
        arguments.add(workDirectory.toAbsolutePath().normalize().toString());
        switch (command) {
            case GENERATE_DEFAULT_CONTRACT -> {
                requireAbsent(getDefaultContractFile(), "defaultContractFile", command);
                requireAbsent(getIndexFile(), "indexFile", command);
                requireAbsent(getAnimationHistoryFile(), "animationHistoryFile", command);
            }
            case GENERATE_INDEX -> {
                arguments.add(required(getDefaultContractFile(), "defaultContractFile", command));
                arguments.add(required(getAnimationHistoryFile(), "animationHistoryFile", command));
                requireAbsent(getIndexFile(), "indexFile", command);
            }
            case VERIFY -> {
                arguments.add(required(getIndexFile(), "indexFile", command));
                arguments.add(required(getDefaultContractFile(), "defaultContractFile", command));
                requireAbsent(getAnimationHistoryFile(), "animationHistoryFile", command);
            }
            default -> throw new GradleException("Unknown builtin model tool command: " + command);
        }
        return List.copyOf(arguments);
    }

    private static String required(RegularFileProperty property, String name, String command) {
        if (!property.isPresent()) {
            throw new GradleException(name + " is required for builtin model tool command " + command);
        }
        return property.get().getAsFile().getAbsolutePath();
    }

    private static void requireAbsent(RegularFileProperty property, String name, String command) {
        if (property.isPresent()) {
            throw new GradleException(name + " is not valid for builtin model tool command " + command);
        }
    }
}
