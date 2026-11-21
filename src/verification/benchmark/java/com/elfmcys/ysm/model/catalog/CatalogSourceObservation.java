package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.benchmark.ReadHandleProbe;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Keeps byte and call observations outside the uninstrumented latency workload. */
public final class CatalogSourceObservation {
    public static void main(String[] args) throws Exception {
        var results = new ArrayList<Object>();
        for (var kind : new String[] {"directory", "zip", "7z"}) {
            for (var cold : new boolean[] {true, false}) {
                var source = new CatalogSourceBenchmark.Cold();
                source.kind = kind;
                source.setup();
                try {
                    ReadHandleProbe.clear();
                    ReadHandleProbe.resetSources();
                    var start = ReadHandleProbe.queries().size();
                    if (cold)
                        source.removeCandidate();
                    try (var content = source.resolver.resolveMaterialized(source.observation).resolvedContent()) {
                        content.content().modelId();
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("kind", kind);
                    result.put("state", cold ? "cold" : "warm");
                    result.put("captureCalls", ReadHandleProbe.sourceCalls(0));
                    result.put("parseCalls", ReadHandleProbe.sourceCalls(1));
                    result.put("archiveGetFileCalls", ReadHandleProbe.sourceCalls(2));
                    result.put("archiveOpenCalls", ReadHandleProbe.sourceCalls(3));
                    result.put("archiveMembersCalls", ReadHandleProbe.sourceCalls(4));
                    result.put("capturedRetainedBytes", ReadHandleProbe.capturedBytes());
                    result.put("archiveMemberReturnedBytes", ReadHandleProbe.archiveMemberBytes());
                    result.put("queries", ReadHandleProbe.queries().subList(start, ReadHandleProbe.queries().size()));
                    result.put("handles", ReadHandleProbe.snapshot());
                    results.add(result);
                } finally {
                    source.close();
                }
            }
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(results));
    }
}
