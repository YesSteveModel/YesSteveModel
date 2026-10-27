package com.elfmcys.ysm.verification;

import com.google.gson.GsonBuilder;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

public final class CatalogLinksVerification {
    private static final List<String> TESTS = List.of("model.catalog.source.ModelSourceDiscoveryTest",
        "model.catalog.RawModelImporterTest", "model.catalog.CatalogReconcilerTest",
        "model.catalog.ReloadableModelCatalogTest", "network.dispatch.ServerAssetTransfersTest",
        "format.parser.ModelParserTest", "format.schema.model.ModelFileViewTest",
        "model.resource.client.asset.PageImagesTest", "model.storage.PreviewStoreTest",
        "model.storage.ConvertedSourceIndexStoreTest", "model.service.ServerModelServiceReloadTest");

    private CatalogLinksVerification() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4)
            throw new IllegalArgumentException("Expected <fixtures> <evidence> <platform> [windows-junction-root]");
        var fixtures = Path.of(args[0]).toAbsolutePath().normalize();
        var evidence = Files.createDirectories(Path.of(args[1]).toAbsolutePath().normalize());
        var archive =
            Path.of(CatalogLinksVerification.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(archive)) {
            // Existing fixtures use file-backed resources, so retain their real adapter boundary.
            var runtime = Files.createDirectories(fixtures).resolve("runtime");
            Files.createDirectory(runtime);
            try (var jar = new JarFile(archive.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory())
                        continue;
                    var destination = runtime.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(runtime))
                        throw new IllegalStateException("Archive entry escaped runtime: " + entry.getName());
                    Files.createDirectories(destination.getParent());
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.writeString(evidence.resolve("artifact.json"), json(Map.of("jarSha256", sha256(archive),
                    "javaRevision", jar.getManifest().getMainAttributes().getValue("Verification-Revision"))));
            }
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + Files.createDirectories(fixtures.resolve("temporary")), "-cp", runtime.toString(),
                CatalogLinksVerification.class.getName()));
            command.addAll(List.of(args));
            var process = new ProcessBuilder(command).inheritIO().start();
            System.exit(process.waitFor());
        }
        var nativePath = Path.of(java.util.Objects.requireNonNull(System.getenv("YSM_NATIVE_PATH")));
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("startedAt", Instant.now().toString());
        metadata.put("platform", args[2]);
        metadata.put("java", System.getProperty("java.runtime.version"));
        metadata.put("os", System.getProperty("os.name"));
        metadata.put("fixtures", fixtures.toString());
        metadata.put("nativePath", nativePath.toString());
        metadata.put("nativeSha256", sha256(nativePath));
        metadata.put("command", ProcessHandle.current().info().commandLine().orElse("unavailable"));
        if (args.length == 4) {
            var junction = Path.of(args[3]).toAbsolutePath();
            System.setProperty("ysm.links.junctionRoot", junction.toString());
            var attributes = Files.readAttributes(junction, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            metadata.put("junction",
                Map.of("path", junction.toString(), "realPath", junction.toRealPath().toString(), "directory",
                    attributes.isDirectory(), "symbolicLink", attributes.isSymbolicLink(), "other",
                    attributes.isOther(), "provider", junction.getFileSystem().provider().getScheme()));
        }
        var request = LauncherDiscoveryRequestBuilder.request();
        var selected = System.getenv("YSM_VERIFICATION_TESTS");
        var tests = selected == null || selected.isBlank() ? TESTS : List.of(selected.split(","));
        metadata.put("suites", tests);
        for (var test : tests)
            request.selectors(DiscoverySelectors.selectClass("com.elfmcys.ysm." + test));
        var summary = new SummaryGeneratingListener();
        var results = new ArrayList<Map<String, Object>>();
        var listener = new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier test, TestExecutionResult result) {
                if (!test.isTest() && result.getStatus() != TestExecutionResult.Status.FAILED)
                    return;
                var item = new LinkedHashMap<String, Object>();
                item.put("id", test.getUniqueId());
                item.put("source", test.getSource().map(Object::toString).orElse("unavailable"));
                item.put("result", result.getStatus().name());
                result.getThrowable().ifPresent(failure -> item.put("failure", failure.toString()));
                results.add(item);
                System.out.println("CATALOG_LINKS " + result.getStatus() + " " + test.getDisplayName());
            }

            @Override
            public void executionSkipped(TestIdentifier test, String reason) {
                results.add(Map.of("id", test.getUniqueId(), "result", "SKIPPED", "reason", reason));
            }
        };
        LauncherFactory.create().execute(request.build(), summary, listener);
        summary.getSummary().printTo(new PrintWriter(System.out, true));
        summary.getSummary().printFailuresTo(new PrintWriter(System.out, true));
        metadata.put("finishedAt", Instant.now().toString());
        metadata.put("passed", summary.getSummary().getTestsSucceededCount());
        metadata.put("failed", summary.getSummary().getTotalFailureCount());
        metadata.put("aborted", summary.getSummary().getTestsAbortedCount());
        metadata.put("skipped", summary.getSummary().getTestsSkippedCount());
        metadata.put("results", results);
        Files.writeString(evidence.resolve("results.json"), json(metadata));
        if (summary.getSummary().getTotalFailureCount() != 0)
            System.exit(1);
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[65536];
            for (int length; (length = input.read(buffer)) != -1;)
                digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String json(Object value) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n";
    }
}
