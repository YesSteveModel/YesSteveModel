package com.elfmcys.ysm.client.command;

import com.elfmcys.ysm.expression.host.HostSymbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientRootCommandTest {
    @Test
    void mapsFrozenHostSymbolsToMolangSuggestions() {
        assertEquals("q.health", ClientRootCommand.sourceName(HostSymbol.QUERY_HEALTH));
        assertEquals("math.random", ClientRootCommand.sourceName(HostSymbol.MATH_RANDOM));
        assertEquals("ctrl.idle", ClientRootCommand.sourceName(HostSymbol.CTRL_IDLE));
        assertEquals("o", ClientRootCommand.sourceName(HostSymbol.GLOBAL_O));
    }
}
