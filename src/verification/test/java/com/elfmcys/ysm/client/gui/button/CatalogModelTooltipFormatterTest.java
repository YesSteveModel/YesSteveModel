package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.proto.mixel.manifest.info.ExportInfoProto;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class CatalogModelTooltipFormatterTest {
    private static final String PREFIX = "gui.yes_steve_model.catalog.tooltip.";
    private static final String MODEL = "gui.yes_steve_model.model.";
    private static final CatalogModelTooltipFormatter.Translator TRANSLATOR =
        CatalogModelTooltipFormatterTest::translate;

    @Test
    void summaryPreservesLegacyLinesAuthorsAndStylesAlongsideLoadErrors() {
        var input = input(new CompletionException(new IllegalStateException("C:/PHYSICAL_ROOT_SECRET/preview failed",
            new IOException("D:/FINAL_TARGET_SECRET/missing chunk"))));
        var lines = CatalogModelTooltipFormatter.format(input, false, TRANSLATOR);
        var text = lines.stream().map(Component::getString).toList();

        assertEquals(List.of("Localized Name", "Localized Name", "First line", "", "Second line", " ",
            "Authors: Role 0: Author 0丨Author 1丨Role 2: Author 2丨Author 3丨Role 4: Author 4丨Author 5丨Role 6: Author 6",
            "License: CC-BY-4.0", "Load error: Content validation failed", " ", "Stats: 12 bones, 34 cubes, 56 faces",
            "Textures: 7"), text);
        assertEquals(ChatFormatting.GOLD.getColor(), lines.get(0).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.RED.getColor(), lines.get(8).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.GRAY.getColor(), lines.get(11).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.DARK_GRAY.getColor(),
            lines.get(6).getSiblings().get(0).getStyle().getColor().getValue());
        assertFalse(text.stream().anyMatch(value -> value.contains("catalog/path")));
    }

    @Test
    void shiftRetainsExportDetailsAndDoesNotExposeHostExceptionText() {
        var previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            var lines = CatalogModelTooltipFormatter
                .format(input(new IllegalStateException("C:/PHYSICAL_ROOT_SECRET/preview failed",
                    new IOException("D:/FINAL_TARGET_SECRET/missing chunk"))), true, TRANSLATOR);
            var text = lines.stream().map(Component::getString).toList();
            assertEquals(
                List.of("Path: catalog/path", "Hash: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "Extra: fixture", "Timestamp: 2023-11-14 22:13:20", "Load error: Content validation failed",
                    "Root cause: Content validation failed"),
                text.subList(8, 14));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void metadataAbsenceDoesNotInventATitleOrExportDetails() {
        var input = new CatalogModelTooltipFormatter.Input("", "", List.of(), "", "path", "hash", null,
            new CatalogModelTooltipFormatter.Stats(0, 0, 0, 0), null);
        assertEquals(List.of("Path: path", "Hash: hash", " ", "Stats: 0 bones, 0 cubes, 0 faces", "Textures: 0"),
            CatalogModelTooltipFormatter.format(input, true, TRANSLATOR).stream().map(Component::getString).toList());
    }

    @Test
    void missingPreviewGuidanceUsesCurrentRoleAndNeverAppearsForFailure() {
        var administrator = CatalogModelTooltipFormatter.format(input(null), false, TRANSLATOR, true, true);
        var player = CatalogModelTooltipFormatter.format(input(null), false, TRANSLATOR, true, false);
        assertTrue(administrator.stream().anyMatch(line -> line.getString().equals(PREFIX + "preview_repair_command")));
        assertTrue(administrator.stream().anyMatch(line -> line.getString().equals(PREFIX + "preview_repair_export")));
        assertFalse(administrator.stream().anyMatch(line -> line.getString().equals(PREFIX + "preview_contact_admin")));
        assertTrue(player.stream().anyMatch(line -> line.getString().equals(PREFIX + "preview_contact_admin")));
        var failure =
            CatalogModelTooltipFormatter.format(input(new IOException("busy")), false, TRANSLATOR, true, true);
        assertFalse(failure.stream().anyMatch(line -> line.getString().contains("preview_")));
    }

    private static CatalogModelTooltipFormatter.Input input(Throwable error) {
        var authors = new ArrayList<CatalogModelTooltipFormatter.Author>();
        for (var index = 0; index < 7; index++)
            authors
                .add(new CatalogModelTooltipFormatter.Author("Author " + index, index % 2 == 0 ? "Role " + index : ""));
        return new CatalogModelTooltipFormatter.Input("Localized Name", "Localized Name\nFirst line\r\n\nSecond line",
            authors, "CC-BY-4.0", "catalog/path", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ExportInfoProto.newBuilder().setTimestamp(1700000000).setExtra("fixture").build(),
            new CatalogModelTooltipFormatter.Stats(12, 34, 56, 7), error);
    }

    private static MutableComponent translate(String key, Object... arguments) {
        var translations = Map.ofEntries(Map.entry(MODEL + "authors", "Authors: %s"),
            Map.entry(MODEL + "license", "License: %s"), Map.entry(PREFIX + "error", "Load error: %s"),
            Map.entry(MODEL + "file", "Path: %s"), Map.entry(MODEL + "hash", "Hash: %s"),
            Map.entry(MODEL + "extra", "Extra: %s"), Map.entry(MODEL + "timestamp", "Timestamp: %s"),
            Map.entry(MODEL + "main_model_info", "Stats: %s bones, %s cubes, %s faces"),
            Map.entry(PREFIX + "root_cause", "Root cause: %s"), Map.entry(MODEL + "texture_info", "Textures: %s"));
        var pattern = translations.getOrDefault(key, key);
        if (arguments.length == 1 && arguments[0] instanceof Component component)
            return Component.literal(pattern.substring(0, pattern.indexOf("%s"))).append(component);
        return Component.literal(pattern.formatted(arguments));
    }
}
