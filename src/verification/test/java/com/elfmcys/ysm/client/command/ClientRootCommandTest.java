package com.elfmcys.ysm.client.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClientRootCommandTest {
    @Test
    void registeredTreeKeepsLocalMolangAndAcceptsClearSelectors() throws Exception {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        ClientRootCommand.register(dispatcher);
        var root = dispatcher.getRoot().getChild("ysmclient");
        assertNotNull(root.getChild("molang"));
        var clear = root.getChild("roaming");
        assertNotNull(clear.getChild("clear").getChild("model"));
        var subtree = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        subtree.getRoot().addChild(clear);
        var source = (net.minecraft.commands.CommandSourceStack)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.commands.CommandSourceStack.class);
        for (var selector : java.util.List.of("default", "id:missing/model@1.0.0", "\"local:folder/model one.mxc\"")) {
            var parsed = subtree.parse("roaming clear " + selector, source);
            assertFalse(parsed.getReader().canRead());
            assertNotNull(parsed.getContext().getCommand());
        }
    }

    @Test
    void mapsFrozenHostSymbolsToMolangSuggestions() {
        assertEquals("q.health", ClientRootCommand.sourceName("query:health"));
        assertEquals("math.random", ClientRootCommand.sourceName("math:random"));
        assertEquals("ctrl.idle", ClientRootCommand.sourceName("ctrl:idle"));
        assertEquals("o", ClientRootCommand.sourceName("global:o"));
    }
}
