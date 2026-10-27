package com.elfmcys.ysm.mock.evidence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.TreeSet;

public final class EvidenceJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private EvidenceJson() {}

    public static byte[] canonicalBytes(Object value) {
        var tree = canonicalize(GSON.toJsonTree(value));
        return GSON.toJson(tree).getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("The JVM must provide SHA-256", exception);
        }
    }

    static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static void writeJsonNew(Path path, Object value) throws IOException {
        writeNew(path, canonicalBytes(value));
    }

    static void writeJsonIdentical(Path path, Object value) throws IOException {
        var bytes = canonicalBytes(value);
        try {
            writeNew(path, bytes);
        } catch (FileAlreadyExistsException exception) {
            if (!Arrays.equals(bytes, Files.readAllBytes(path))) {
                throw new IOException("Existing immutable metadata differs: " + path, exception);
            }
        }
    }

    private static JsonElement canonicalize(JsonElement value) {
        if (value.isJsonObject()) {
            var source = value.getAsJsonObject();
            var result = new JsonObject();
            for (var key : new TreeSet<>(source.keySet())) {
                result.add(key, canonicalize(source.get(key)));
            }
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var element : value.getAsJsonArray()) {
                result.add(canonicalize(element));
            }
            return result;
        }
        return value.deepCopy();
    }
}
