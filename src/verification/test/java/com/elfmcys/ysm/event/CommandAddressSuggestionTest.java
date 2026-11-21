package com.elfmcys.ysm.event;

import com.mojang.brigadier.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandAddressSuggestionTest {
    @Test
    void selectorArgumentPreservesCanonicalPunctuationAndQuotedPaths() throws Exception {
        var parser = com.elfmcys.ysm.command.ModelSelectorArgument.selector();
        for (var value : List.of("id:Author/model@1.2.0+pc", "id:hash:" + "a".repeat(64), "global:pack/a.mxc")) {
            var reader = new StringReader(value + " texture");
            assertEquals(value, parser.parse(reader));
            assertEquals(" texture", reader.getRemaining());
        }
        assertEquals("local:a \"quoted\"/猫.mxc", parser.parse(new StringReader("\"local:a \\\"quoted\\\"/猫.mxc\"")));
    }
}
