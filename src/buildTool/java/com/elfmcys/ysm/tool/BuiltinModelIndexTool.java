package com.elfmcys.ysm.tool;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelMaterializer;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.google.gson.stream.JsonWriter;
import dev.semaweave.ProgramKey;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.CompilationUnit;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.DefaultExpressionAotCompiler;
import dev.semaweave.compilation.ProgramReplacement;
import dev.semaweave.compilation.TaskContext;
import dev.semaweave.program.PortableProgramCodecs;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuiltinModelIndexTool {
    private static final ModelPath DEFAULT_PATH = new ModelPath("default");
    private static final int VERIFICATION_RECEIPT_FORMAT_VERSION = 3;

    private BuiltinModelIndexTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw usage();
        }
        var expectedArguments = switch (args[0]) {
            case "generate-default-contract" -> 5;
            case "generate-index", "verify" -> 7;
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
        if (args.length != expectedArguments) {
            throw usage();
        }

        var libPath = Path.of(args[1]).toAbsolutePath().normalize().toString();
        YesSteveModel.LOGGER.error("Loading native lib: {}", libPath);
        NativeRuntime.load(libPath);
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(Level.INFO));
        var sourceRoot = Path.of(args[2]).toAbsolutePath().normalize();
        var resultFile = Path.of(args[3]).toAbsolutePath().normalize();
        var workDirectory = Path.of(args[4]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "generate-default-contract" -> generateDefaultContract(sourceRoot, resultFile, workDirectory);
            case "generate-index" -> generateIndex(sourceRoot, resultFile,
                Path.of(args[5]).toAbsolutePath().normalize(), Path.of(args[6]).toAbsolutePath().normalize());
            case "verify" -> verify(sourceRoot, resultFile, workDirectory,
                Path.of(args[5]).toAbsolutePath().normalize(), Path.of(args[6]).toAbsolutePath().normalize());
            default -> throw new AssertionError("Command was validated before native load");
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage:\n" + "  generate-default-contract <native-library> <default-root> <contract-file> <work-dir>\n"
                + "  generate-index <native-library> <builtin-root> <index-file> <work-dir> "
                + "<default-contract> <history-file>\n"
                + "  verify <native-library> <builtin-root> <receipt-file> <work-dir> "
                + "<index-file> <default-contract>");
    }

    private static void generateDefaultContract(Path defaultRoot, Path contractFile, Path workDirectory)
        throws IOException {
        Files.deleteIfExists(contractFile);
        if (!Files.isDirectory(defaultRoot)) {
            throw new IOException("Builtin default model root is unavailable: " + defaultRoot);
        }
        Files.createDirectories(workDirectory);

        final Hash256 scanned;
        try (var vfs = new Directory(defaultRoot)) {
            scanned = ModelParser.scanModelHash(vfs);
        }
        var outputDirectory = workDirectory.resolve("converted");
        Files.createDirectories(outputDirectory);
        final Path converted;
        try (var vfs = new Directory(defaultRoot)) {
            converted = ModelParser.parseBuiltinDefault(vfs, outputDirectory);
        }
        var location = new CatalogLocation(CatalogRootKind.BUILTIN, DEFAULT_PATH);
        var handle = openIndexed(converted, location);
        if (!scanned.equals(handle.representation().modelId())) {
            throw new IOException("Builtin default full-conversion hash mismatch: dryRun=" + scanned + ", converted="
                + handle.representation().modelId());
        }
        var materialized = BuiltinModelMaterializer.materialize(handle);
        if (!scanned.equals(materialized.modelHash())) {
            throw new IOException("Builtin default materialization hash mismatch: dryRun=" + scanned + ", materialized="
                + materialized.modelHash());
        }

        var contract = createDefaultContract(scanned, materialized.animationHashes());
        writeIndexAtomically(contract, contractFile);
        System.out.printf("Generated builtin default contract: modelHash=%s defaultAnimations=%d output=%s%n", scanned,
            contract.animationEntries().size(), contractFile);
    }

    private static void generateIndex(Path builtinRoot, Path indexFile, Path defaultContractFile, Path historyFile)
        throws IOException {
        Files.deleteIfExists(indexFile);
        var discovery = discoverRawDirectories(builtinRoot);
        var hashes = new LinkedHashMap<ModelPath, Hash256>();
        for (var source : discovery.models()) {
            var path = ModelPath.relativeTo(builtinRoot, source);
            final Hash256 scanned;
            try (var vfs = new Directory(source)) {
                scanned = ModelParser.scanModelHash(vfs);
            }
            hashes.put(path, scanned);
        }

        var defaultContract = BuiltinModelIndex.read(defaultContractFile);
        var history = BuiltinModelIndex.readAnimationHistory(historyFile);
        var index = createIndex(hashes, defaultContract, history);
        writeIndexAtomically(index, indexFile);
        System.out.printf(
            "Generated builtin model index from content hashes: models=%d " + "defaultAnimations=%d output=%s%n",
            index.entries().size(), index.animationEntries().size(), indexFile);
    }

    private static void verify(Path builtinRoot, Path receiptFile, Path workDirectory, Path indexFile,
        Path defaultContractFile) throws IOException {
        Files.deleteIfExists(receiptFile);
        var index = BuiltinModelIndex.read(indexFile);
        var defaultContract = BuiltinModelIndex.read(defaultContractFile);
        validateDefaultContract(index, defaultContract);

        var discovery = discoverRawDirectories(builtinRoot);
        var discoveredPaths =
            discovery.models().stream().map(source -> ModelPath.relativeTo(builtinRoot, source)).toList();
        index.validateCoverage(discoveredPaths);
        Files.createDirectories(workDirectory);

        var materializedModels = 0;
        var programs = new ArrayList<BuiltinProgramEntry>();
        var candidateCount = 0;
        var executableCount = 0;
        var literalCount = 0;
        var programCount = 0;
        var fallbackCount = 0;
        var failureCount = 0;
        var callableEntryCount = 0;
        var eventEntryCount = 0;
        var rejectedSuffixCount = 0;
        var aotCompiledTargetCount = 0;
        var aotCompiledSetCount = 0;
        var replacements = new ArrayList<String>();
        System.out.println("BUILTIN_RESOURCE_PROCESS," + ProcessHandle.current().pid());
        var host = ExpressionHost.create(HostAvailability.all());
        var analysis = new java.util.EnumMap<dev.semaweave.compilation.EngineMetrics.AnalysisCounter, Long>(
            dev.semaweave.compilation.EngineMetrics.AnalysisCounter.class);
        var compiler = new DefaultExpressionAotCompiler<MolangContext>(new JaninoJavaCompilerBackend(),
            new dev.semaweave.compilation.EngineMetrics() {
                @Override
                public void recordAnalysisWork(CompilationKind kind, AnalysisCounter counter, long delta) {
                    analysis.merge(counter, delta, Long::sum);
                }
            });
        for (var source : discovery.models()) {
            var path = ModelPath.relativeTo(builtinRoot, source);
            System.out.printf("Inventorying builtin model: %s%n", path);
            var expected = index.require(path);
            var outputDirectory = workDirectory.resolve(path.value());
            Files.createDirectories(outputDirectory);
            final Path converted;
            try (var vfs = new Directory(source)) {
                try {
                    converted = path.equals(DEFAULT_PATH) ? ModelParser.parseBuiltinDefault(vfs, outputDirectory)
                        : ModelParser.parse(vfs, outputDirectory, index);
                } catch (RuntimeException failure) {
                    throw new IOException("Failed to lower builtin model " + path, failure);
                }
            }
            var location = new CatalogLocation(CatalogRootKind.BUILTIN, path);
            var handle = openIndexed(converted, location);
            var actual = handle.representation().modelId();
            if (!expected.equals(actual)) {
                throw new IOException("Builtin full-conversion hash mismatch at " + path + ": index=" + expected
                    + ", converted=" + actual);
            }
            var compiledTargets = new java.util.HashSet<String>();
            var compiledSets = new int[1];
            var materialized = BuiltinModelMaterializer.materialize(handle, (targetId, inventory) -> {
                if (!inventory.programs().isEmpty()) {
                    verifyAotCompilation(host, compiler, inventory, path + "/" + targetId, analysis)
                        .forEach(value -> replacements.add(path + "/" + value));
                    compiledTargets.add(targetId);
                    compiledSets[0]++;
                }
            });
            aotCompiledTargetCount += compiledTargets.size();
            aotCompiledSetCount += compiledSets[0];
            if (!expected.equals(materialized.modelHash())) {
                throw new IOException("Builtin materialization hash mismatch at " + path + ": index=" + expected
                    + ", materialized=" + materialized.modelHash());
            }
            materialized.programManifest().forEach(entry -> programs.add(new BuiltinProgramEntry(path.value(), entry)));
            candidateCount += materialized.programCounts().candidates();
            executableCount += materialized.programCounts().executables();
            literalCount += materialized.programCounts().literals();
            programCount += materialized.programCounts().programs();
            fallbackCount += materialized.programCounts().fallbacks();
            failureCount += materialized.programCounts().failures();
            callableEntryCount += materialized.programCounts().callableEntries();
            eventEntryCount += materialized.programCounts().eventEntries();
            rejectedSuffixCount += materialized.programCounts().rejectedSuffixes();
            materializedModels++;
        }

        if (!replacements.isEmpty()) {
            throw new IOException("Builtin AOT selected " + replacements.size() + " expression replacements:\n"
                + String.join("\n", replacements));
        }

        programs
            .sort(Comparator.comparing(BuiltinProgramEntry::modelPath).thenComparing(value -> value.entry().location())
                .thenComparing(value -> value.entry().role().name()).thenComparing(value -> value.entry().keyHex()));
        var referencedSymbols =
            programs.stream().flatMap(value -> value.entry().referencedSymbols().stream()).distinct().sorted().toList();
        var productionLinkage = HostLinkage.production(host);
        var receipt = verificationReport(ModelHashing.blake3(indexFile), materializedModels,
            new ModelProgramInventory.Counts(candidateCount, executableCount, literalCount, programCount, fallbackCount,
                failureCount, callableEntryCount, eventEntryCount, rejectedSuffixCount),
            aotCompiledTargetCount, aotCompiledSetCount, programs,
            new HostRegistryClosure(host.registry().fingerprint().value().toString(),
                host.hostAbi().fingerprint().value().toString(), productionLinkage.fingerprint().value().toString(),
                host.manifest().size(), productionLinkage.descriptorManifest().size(),
                Math.toIntExact(referencedSymbols.stream().filter(value -> value.startsWith("EXTERNAL:")).count())));
        writeTextAtomically(receipt, receiptFile);
        System.out.printf(
            "Verified builtin full materialization and program inventory: "
                + "models=%d targets=%d sets=%d programs=%d receipt=%s%n",
            materializedModels, aotCompiledTargetCount, aotCompiledSetCount, programCount, receiptFile);
    }

    private static List<String> verifyAotCompilation(ExpressionHost host,
        DefaultExpressionAotCompiler<MolangContext> compiler, ModelProgramInventory.Result inventory, String target,
        java.util.Map<dev.semaweave.compilation.EngineMetrics.AnalysisCounter, Long> analysis) throws Exception {
        var builder = CompilationUnit
            .builder(host.compilationPolicy(), PortableProgramCodecs.v1(), ExpressionIdentityHash.SHA_256)
            .addPrograms(inventory.programs());
        inventory.roamingSlots().forEach(builder::addRoamingInstanceSlot);
        var unit = builder.build();
        analysis.clear();
        long started = System.nanoTime();
        var artifact = compiler.compile(TaskContext.NONE, unit, host.hostAbi(), host.registry(),
            CompilerOptions.DEFAULT, host.expressionBinding().bindingPlan(), ExpressionIdentityHash.SHA_256);
        var resources = new LinkedHashMap<String, Object>();
        resources.put("target", target);
        resources.put("set", inventory.bindings().get(0).location().set().id());
        resources.put("coldNanos", System.nanoTime() - started);
        resources.put("identity", artifact.manifest().identity().artifactFingerprint().toString());
        resources.put("metrics", artifact.report().metrics());
        resources.put("analysis", new java.util.EnumMap<>(analysis));
        resources.put("limits", host.compilationPolicy().compilerLimits());
        resources.put("staticInputPrograms", unit.programs().size());
        resources.put("typedMethods", artifact.report().optimizations().values().get("typed-methods"));
        resources.put("variants",
            artifact.catalog().descriptors().stream().mapToLong(value -> value.variants().size()).sum());
        System.out.println("BUILTIN_RESOURCE," + new com.google.gson.Gson().toJson(resources));
        var locations = new LinkedHashMap<ProgramKey, ModelProgramInventory.SemanticLocation>();
        for (var binding : inventory.bindings()) {
            if (locations.put(binding.source().key(), binding.location()) != null) {
                throw new IllegalArgumentException("Duplicate builtin program key: " + binding.source().key());
            }
        }
        return replacementDescriptions(artifact.replacements(), locations);
    }

    static List<String> replacementDescriptions(List<ProgramReplacement> replacements,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> currentLocations) {
        return replacements.stream().map(replacement -> {
            var location = currentLocations.get(replacement.programKey());
            if (location == null) {
                throw new IllegalArgumentException("Replacement has no current builtin location");
            }
            var discriminator = location.discriminator().isEmpty() ? "" : ":" + location.discriminator();
            return location.targetId() + "/" + location.location() + " [" + location.role() + discriminator + "]"
                + ": reason=" + replacement.reason() + " result=" + replacement.result() + " node="
                + replacement.nodePath() + " message=" + replacement.message();
        }).toList();
    }

    static BuiltinModelIndex createDefaultContract(Hash256 defaultHash,
        Map<DefaultAnimationKey, Hash256> currentAnimations) throws IOException {
        return BuiltinModelIndex.of(Map.of(DEFAULT_PATH, defaultHash), currentAnimations, Map.of());
    }

    private static ManagedContainer openIndexed(Path file, CatalogLocation location) throws IOException {
        final com.elfmcys.ysm.model.domain.ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        return ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file));
    }

    static BuiltinModelIndex createIndex(Map<ModelPath, Hash256> modelHashes, BuiltinModelIndex defaultContract,
        Map<DefaultAnimationKey, Set<Hash256>> history) throws IOException {
        requireDefaultOnlyContract(defaultContract);
        var scannedDefault = modelHashes.get(DEFAULT_PATH);
        if (scannedDefault == null) {
            throw new IOException("Builtin scan did not discover the default model");
        }
        var contractedDefault = defaultContract.require(DEFAULT_PATH);
        if (!scannedDefault.equals(contractedDefault)) {
            throw new IOException(
                "Builtin default contract hash mismatch: scan=" + scannedDefault + ", contract=" + contractedDefault);
        }
        return BuiltinModelIndex.of(modelHashes, currentAnimationHashes(defaultContract), history);
    }

    static void validateDefaultContract(BuiltinModelIndex index, BuiltinModelIndex defaultContract) throws IOException {
        requireDefaultOnlyContract(defaultContract);
        var indexedDefault = index.require(DEFAULT_PATH);
        var contractedDefault = defaultContract.require(DEFAULT_PATH);
        if (!indexedDefault.equals(contractedDefault)) {
            throw new IOException("Builtin default proof does not match final index: index=" + indexedDefault
                + ", contract=" + contractedDefault);
        }
        var indexedAnimations = currentAnimationHashes(index);
        var contractedAnimations = currentAnimationHashes(defaultContract);
        if (!indexedAnimations.equals(contractedAnimations)) {
            throw new IOException("Builtin default animation proof does not match final index");
        }
    }

    static String verificationReceipt(Hash256 indexHash, int modelCount) {
        if (modelCount < 1) {
            throw new IllegalArgumentException("Verified builtin model count must be positive");
        }
        return "{\n" + "  \"formatVersion\": " + VERIFICATION_RECEIPT_FORMAT_VERSION + ",\n"
            + "  \"builtinIndexHash\": \"" + indexHash + "\",\n" + "  \"verifiedModelCount\": " + modelCount + "\n"
            + "}\n";
    }

    private static String verificationReport(Hash256 indexHash, int modelCount, ModelProgramInventory.Counts counts,
        int aotCompiledTargetCount, int aotCompiledSetCount, List<BuiltinProgramEntry> programs,
        HostRegistryClosure registryClosure) throws IOException {
        var output = new StringWriter();
        try (var writer = new JsonWriter(output)) {
            writer.setIndent("  ");
            writer.beginObject();
            writer.name("formatVersion").value(VERIFICATION_RECEIPT_FORMAT_VERSION);
            writer.name("builtinIndexHash").value(indexHash.toString());
            writer.name("verifiedModelCount").value(modelCount);
            writer.name("aotCompiledTargetCount").value(aotCompiledTargetCount);
            writer.name("aotCompiledSetCount").value(aotCompiledSetCount);
            writer.name("eventCatalogFingerprint")
                .value(MolangEventCatalog.production().fingerprint().value().toString());
            writer.name("counts").beginObject();
            writer.name("candidates").value(counts.candidates());
            writer.name("executables").value(counts.executables());
            writer.name("literals").value(counts.literals());
            writer.name("programs").value(counts.programs());
            writer.name("fallbacks").value(counts.fallbacks());
            writer.name("failures").value(counts.failures());
            writer.name("callableEntries").value(counts.callableEntries());
            writer.name("eventEntries").value(counts.eventEntries());
            writer.name("rejectedSuffixes").value(counts.rejectedSuffixes());
            writer.endObject();
            writer.name("hostRegistry").beginObject();
            writer.name("registryFingerprint").value(registryClosure.registryFingerprint());
            writer.name("hostAbiFingerprint").value(registryClosure.hostAbiFingerprint());
            writer.name("productionLinkageFingerprint").value(registryClosure.productionLinkageFingerprint());
            writer.name("descriptorCount").value(registryClosure.descriptorCount());
            writer.name("linkedDescriptorCount").value(registryClosure.linkedDescriptorCount());
            writer.name("referencedExternalSymbolCount").value(registryClosure.referencedExternalSymbolCount());
            writer.name("missingDescriptorCount").value(0);
            writer.endObject();
            writer.name("programInventory").beginArray();
            for (var value : programs) {
                var entry = value.entry();
                writer.beginObject();
                writer.name("modelPath").value(value.modelPath());
                writer.name("sourceLocator").value(entry.location());
                writer.name("role").value(entry.role().name());
                writer.name("classifier").value(entry.classifier());
                writer.name("keyBytes").value(entry.keyHex());
                writer.name("keyHash").value(entry.keySha256());
                writer.name("signature").value(entry.signature());
                writer.name("fallback").value(entry.fallback());
                writer.name("referencedSymbols").beginArray();
                for (var symbol : entry.referencedSymbols())
                    writer.value(symbol);
                writer.endArray();
                writer.name("loweringResult").value(entry.loweringResult());
                writer.name("entryKind").value(entry.entryKind());
                writer.name("routeId").value(entry.routeId());
                writer.name("payload").value(entry.payload());
                writer.name("result").value(entry.result());
                writer.name("maximumArguments").value(entry.maximumArguments());
                writer.name("handlerSelection").value(entry.handlerSelection());
                writer.name("handlerOrder").value(entry.handlerOrder());
                writer.name("ordering").value(entry.ordering());
                writer.name("fallbackPolicy").value(entry.fallbackPolicy());
                writer.name("effectPolicy").value(entry.effectPolicy());
                writer.name("diagnosticPolicy").value(entry.diagnosticPolicy());
                writer.name("publicReachability").value(entry.publicReachability());
                writer.endObject();
            }
            writer.endArray();
            writer.endObject();
        }
        return output + "\n";
    }

    private record BuiltinProgramEntry(String modelPath, ModelProgramInventory.ManifestEntry entry) {
    }

    private record HostRegistryClosure(String registryFingerprint, String hostAbiFingerprint,
        String productionLinkageFingerprint, int descriptorCount, int linkedDescriptorCount,
        int referencedExternalSymbolCount) {
    }

    private static void requireDefaultOnlyContract(BuiltinModelIndex contract) throws IOException {
        var entries = contract.entries();
        if (entries.size() != 1 || !entries.get(0).path().equals(DEFAULT_PATH)) {
            throw new IOException("Builtin default contract must contain exactly the default model");
        }
        for (var animation : contract.animationEntries()) {
            if (!animation.acceptedPayloadHashes().equals(List.of(animation.currentPayloadHash()))) {
                throw new IOException(
                    "Builtin default contract must contain only current animation hashes: " + animation.key());
            }
        }
    }

    private static Map<DefaultAnimationKey, Hash256> currentAnimationHashes(BuiltinModelIndex index) {
        var animations = new LinkedHashMap<DefaultAnimationKey, Hash256>();
        index.animationEntries().forEach(entry -> animations.put(entry.key(), entry.currentPayloadHash()));
        return Map.copyOf(animations);
    }

    private static ModelSourceDiscovery.Result discoverRawDirectories(Path builtinRoot) throws IOException {
        if (!Files.isDirectory(builtinRoot)) {
            throw new IOException("Builtin model root is unavailable: " + builtinRoot);
        }
        var discovery = ModelSourceDiscovery.discover(builtinRoot);
        if (!discovery.failures().isEmpty()) {
            var failure = discovery.failures().get(0);
            throw new IOException("Failed to discover builtin source: " + failure.path(), failure.error());
        }
        for (var source : discovery.models()) {
            if (!Files.isDirectory(source)) {
                throw new IOException(
                    "Builtin resources support only raw model directories: " + builtinRoot.relativize(source));
            }
        }
        return discovery;
    }

    private static void writeIndexAtomically(BuiltinModelIndex index, Path output) throws IOException {
        writeAtomically(output, index::write);
    }

    private static void writeTextAtomically(String value, Path output) throws IOException {
        writeAtomically(output, temporary -> Files.writeString(temporary, value, StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path output, PathWriter writer) throws IOException {
        output = output.toAbsolutePath().normalize();
        var parent = output.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, output.getFileName() + ".", ".tmp");
        var moved = false;
        try {
            writer.write(temporary);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @FunctionalInterface
    private interface PathWriter {
        void write(Path path) throws IOException;
    }
}
