package com.elfmcys.ysm.mock.classpath;

import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import org.apache.logging.log4j.Level;

import java.nio.file.Files;
import java.nio.file.Path;

/** Process-lifetime native dependency required by production model parsing and codecs. */
public final class MockNativeRuntime {
    private MockNativeRuntime() {}

    public static String initialize(Path library) throws Exception {
        library = library.toAbsolutePath().normalize();
        if (!Files.isRegularFile(library)) {
            throw new IllegalArgumentException("Native library does not exist: " + library);
        }
        NativeRuntime.load(library.toString());
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(Level.INFO));
        return EvidenceJson.sha256(Files.readAllBytes(library));
    }
}
