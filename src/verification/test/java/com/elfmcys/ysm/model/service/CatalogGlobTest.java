package com.elfmcys.ysm.model.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogGlobTest {
    private record Example(String pattern, String path, boolean matched) {
    }

    @Test
    void independentContractTableCoversCodePointsAndLiteralCharacters() {
        var cases = List.of(new Example("pack/*", "pack/a.mxc", true), new Example("pack/*", "pack/sub/b.mxc", true),
            new Example("pack/*", "other/a.mxc", false), new Example("pack/*", "Pack/a.mxc", false),
            new Example("model-?.mxc", "model-a.mxc", true), new Example("model-?.mxc", "model-猫.mxc", true),
            new Example("model-?.mxc", "model-😀.mxc", true), new Example("model-?.mxc", "model-ab.mxc", false),
            new Example("model-?.mxc", "xmodel-a.mxc", false), new Example("a?b", "a/b", true),
            new Example("a?b", "a/xb", false), new Example("a.b", "a.b", true), new Example("a.b", "axb", false),
            new Example("[a]", "[a]", true), new Example("[a]", "a", false), new Example("(a)+$^", "(a)+$^", true),
            new Example("(a)+$^", "aa", false), new Example("", "", true), new Example("", "pack/a.mxc", false),
            new Example("*", "", true), new Example("**", "pack/sub/a.mxc", true),
            new Example("***a**?*", "pack/a😀", true), new Example("?", "😀", true), new Example("??", "😀", false),
            new Example("*😀?", "x😀猫", true), new Example("a\\b", "a\\b", true), new Example("a\\b", "a/b", false),
            new Example("a\\*", "a\\tail", true), new Example("a\\*", "a*", false), new Example("é", "e\u0301", false),
            new Example("é", "é", true), new Example("a*b*c", "abxbyc", true), new Example("a*b*c", "acb", false));
        for (var example : cases)
            assertEquals(example.matched, CatalogGlob.matches(example.pattern, example.path),
                example.pattern + " -> " + example.path);
    }

    @Test
    void longWildcardInputUsesBoundedIteration() {
        assertFalse(CatalogGlob.matches("*a".repeat(400) + "b", "a".repeat(2000)));
        assertTrue(CatalogGlob.matches("*".repeat(2000), "😀/".repeat(2000)));
    }
}
