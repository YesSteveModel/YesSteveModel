package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.model.service.ModelExportService;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.client.server.IntegratedServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExportCommandTest {
    @Test
    void dedicatedHitAndMissPathsAndLowerPermissionRejectBeforeAnyServiceOrSourceWork() throws Exception {
        var dedicated = stack(DedicatedServer.class, 2);
        assertThrows(IllegalStateException.class, () -> ModelExportService.environment(dedicated));
        assertTrue(ModelExportService.export(dedicated, "image-hit", null).isCompletedExceptionally());
        assertTrue(ModelExportService.export(dedicated, "image-miss", null).isCompletedExceptionally());
        var integrated = stack(IntegratedServer.class, 1);
        assertThrows(IllegalStateException.class, () -> ModelExportService.environment(integrated));
        assertTrue(ModelExportService.export(integrated, "image-hit", null).isCompletedExceptionally());
        assertTrue(ModelExportService.export(integrated, "image-miss", null).isCompletedExceptionally());
    }

    @Test
    void commandStringParsingPreservesExplicitEmptySpacesQuotesAndLiteralBackslash() throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(ExportCommand.getAll());
        var source = stack(IntegratedServer.class, 2);
        var fixtures = new String[][] {{"export-all \"\"", ""}, {"export-all pack/*", "pack/*"},
            {"export-all \"a b\"", "a b"}, {"export-all \"a\\\"b\"", "a\"b"}, {"export-all \"a\\\\b\"", "a\\b"},
            {"export-all model-?.mxc", "model-?.mxc"}};
        for (var fixture : fixtures) {
            var parsed = dispatcher.parse(fixture[0], source);
            assertFalse(parsed.getReader().canRead(), fixture[0]);
            assertTrue(parsed.getExceptions().isEmpty(), fixture[0]);
            assertEquals(fixture[1],
                ExportCommand.parsePattern(StringArgumentType.getString(parsed.getContext().build(fixture[0]), "reg")));
        }
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            () -> ExportCommand.parsePattern("a b"));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            () -> ExportCommand.parsePattern("\"a\" trailing"));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            () -> ExportCommand.parsePattern("\"unterminated"));
        var omitted = dispatcher.parse("export-all", source);
        assertNotNull(omitted.getContext().getCommand());
        assertFalse(dispatcher.getRoot().getChild("export-all").canUse(stack(IntegratedServer.class, 1)));
        assertTrue(dispatcher.getRoot().getChild("export-all").canUse(source));
        dispatcher.register(ExportCommand.get());
        var single = dispatcher.parse("export \"pack/a b\" extra metadata", source);
        assertFalse(single.getReader().canRead());
        assertEquals("pack/a b",
            StringArgumentType.getString(single.getContext().build(single.getReader().getString()), "model_id"));
        assertEquals("extra metadata",
            StringArgumentType.getString(single.getContext().build(single.getReader().getString()), "extra"));
    }

    private static CommandSourceStack stack(Class<? extends MinecraftServer> type, int permission) throws Exception {
        var source = (CommandSourceStack)UnsafeUtil.getUnsafe().allocateInstance(CommandSourceStack.class);
        var server = UnsafeUtil.getUnsafe().allocateInstance(type);
        var level = CommandSourceStack.class.getDeclaredField("permissionLevel");
        level.setAccessible(true);
        level.setInt(source, permission);
        var owner = CommandSourceStack.class.getDeclaredField("server");
        owner.setAccessible(true);
        owner.set(source, server);
        return source;
    }
}
