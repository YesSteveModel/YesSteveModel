package com.elfmcys.ysm.model.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelIdTest {
    @Test
    void preservesCanonicalIdentityWithoutNormalization() {
        for (var text : List.of("hash:" + "01".repeat(32), "Owner/name", "owner/Name@1.2.3-a.1+Build")) {
            var id = ModelId.parse(text);
            assertEquals(text, id.toString());
            assertArrayEquals(text.getBytes(StandardCharsets.US_ASCII), id.bytes());
            assertEquals(id, ModelId.parse(text));
        }
        assertNotEquals(ModelId.parse("Owner/name"), ModelId.parse("owner/name"));
        assertEquals("hash:" + "00".repeat(32), ModelId.fromHash(new Hash256(new byte[32])).toString());
        assertThrows(NullPointerException.class, () -> ModelId.fromHash(null));
    }

    @Test
    void acceptsExactlyThe194ByteMaximum() {
        var version = "1.0.0+" + "a".repeat(122);
        var text = "o".repeat(32) + "/" + "n".repeat(32) + "@" + version;
        assertEquals(194, text.length());
        assertEquals(text, ModelId.parse(text).toString());
        for (var length = 195; length <= 255; length++) {
            assertFalse(ModelId.isValid(text + "a".repeat(length - 194)));
        }
    }

    @Test
    void rejectsOtherRepresentationsAndPartialNames() {
        for (var text : List.of("", "00".repeat(32), "hash:" + "AA".repeat(32), "default", "owner", "owner/", "/name",
            "1owner/name", "owner/name/extra", " owner/name", "owner/name ", "owner/名", "owner/name\0", "owner/name@",
            "owner/name@1.0.0@2.0.0")) {
            assertFalse(ModelId.isValid(text), text);
        }
        assertFalse(ModelId.isValid(null));
    }

    @Test
    void collidingJavaHashCodesKeepIndependentNamespaces() {
        var first = ModelId.parse("owner/Aa");
        var second = ModelId.parse("owner/BB");
        assertEquals(first.hashCode(), second.hashCode());
        var values = new HashMap<ModelId, String>();
        values.put(first, "first");
        values.put(second, "second");
        assertEquals("first", values.get(ModelId.parse("owner/Aa")));
        assertEquals("second", values.get(ModelId.parse("owner/BB")));
    }
}
