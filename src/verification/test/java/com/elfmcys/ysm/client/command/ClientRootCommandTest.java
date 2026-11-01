package com.elfmcys.ysm.client.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientRootCommandTest {
    @Test
    void mapsFrozenHostSymbolsToMolangSuggestions() {
        assertEquals("q.health", ClientRootCommand.sourceName("query:health"));
        assertEquals("math.random", ClientRootCommand.sourceName("math:random"));
        assertEquals("ctrl.idle", ClientRootCommand.sourceName("ctrl:idle"));
        assertEquals("o", ClientRootCommand.sourceName("global:o"));
    }
}
