package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.util.ModelIdUtil;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExportInfoProto;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.minecraft.ChatFormatting;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

/** Formats the complete catalog-card tooltip without depending on GUI state. */
public final class CatalogModelTooltipFormatter {
    private static final String MODEL_PREFIX = "gui.yes_steve_model.model.";

    private static final String KEY_PREFIX = "gui.yes_steve_model.catalog.tooltip.";

    private CatalogModelTooltipFormatter() {}

    public static Input input(CatalogModelMetadata catalog, ClientCatalogEntry entry, String locale,
        @Nullable Throwable loadError) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(locale, "locale");

        var info = catalog.info();
        var metadata = info.getMetadata();
        var name = metadata == null ? "" : catalog.localized(locale, "metadata.name", metadata.name());
        var tips = metadata == null ? "" : catalog.localized(locale, "metadata.tips", metadata.tips().orElse(""));
        var authors = new ArrayList<Author>();
        var license = "";
        if (metadata != null) {
            for (var index = 0; index < metadata.authors().size(); index++) {
                var author = metadata.authors().get(index);
                var authorName = catalog.localized(locale, "metadata.authors.%d.name".formatted(index), author.name());
                var role = catalog.localized(locale, "metadata.authors.%d.role".formatted(index), author.role());
                authors.add(new Author(authorName, role));
            }
            license = metadata.license().type();
        }
        var stats = catalog.info().getPlayerStats();
        var textureCount = catalog.representation().view().getPlayer().getTextureNames().size();
        return new Input(name, tips, authors, license, catalog.path(), entry.modelHash().toString(),
            catalog.representation().view().getManifest().info().exportUnsafe(),
            new Stats(stats.bones(), stats.cubes(), stats.faces(), textureCount), loadError);
    }

    public static String displayName(CatalogModelMetadata catalog, String locale) {
        var info = catalog.info();
        var metadata = info.getMetadata();
        var fallback = ModelIdUtil.getFileNameFromPath(catalog.path());
        if (metadata == null || StringUtils.isBlank(metadata.name())) {
            return fallback;
        }
        return StringUtils.firstNonBlank(catalog.localized(locale, "metadata.name", metadata.name()), fallback);
    }

    public static List<Component> format(Input input, boolean detailed, Translator translator) {
        return format(input, detailed, translator, false, false);
    }

    public static List<Component> format(Input input, boolean detailed, Translator translator, boolean missingPreview,
        boolean administrator) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(translator, "translator");
        var lines = new ArrayList<Component>();
        if (!input.name().isBlank())
            lines.add(Component.literal(input.name()).withStyle(ChatFormatting.GOLD));
        if (!input.tips().isBlank()) {
            for (var line : input.tips().replace("\r", "").split("\n"))
                lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        if (!input.authors().isEmpty() || !input.license().isBlank())
            lines.add(Component.literal(" "));
        if (!input.authors().isEmpty()) {
            var authors = input.authors().stream()
                .map(author -> author.role().isEmpty() ? author.name() : author.role() + ": " + author.name()).toList();
            lines.add(detail(translator, "authors", String.join("丨", authors)));
        }
        if (!input.license().isBlank())
            lines.add(detail(translator, "license", input.license()));
        if (detailed) {
            lines.add(detail(translator, "file", input.path()));
            lines.add(detail(translator, "hash", input.hash()));
            var export = input.export();
            if (export != null) {
                if (!export.extra().isBlank())
                    lines.add(detail(translator, "extra", export.extra()));
                if (export.timestamp() != 0)
                    lines.add(detail(translator, "timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .format(Instant.ofEpochSecond(export.timestamp()).atZone(ZoneId.systemDefault()))));
            }
        }
        if (input.loadError() != null) {
            lines.add(translator.translate(KEY_PREFIX + "error", errorSummary(input.loadError()))
                .withStyle(ChatFormatting.RED));
            if (detailed)
                lines.add(translator.translate(KEY_PREFIX + "root_cause", rootCause(input.loadError()))
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (missingPreview) {
            lines.add(translator.translate(KEY_PREFIX + "preview_absent").withStyle(ChatFormatting.GRAY));
            if (administrator) {
                lines.add(translator.translate(KEY_PREFIX + "preview_repair_command").withStyle(ChatFormatting.YELLOW));
                lines.add(translator.translate(KEY_PREFIX + "preview_repair_export").withStyle(ChatFormatting.YELLOW));
            } else {
                lines.add(translator.translate(KEY_PREFIX + "preview_contact_admin").withStyle(ChatFormatting.YELLOW));
            }
        }
        lines.add(Component.literal(" "));
        var stats = input.stats();
        lines.add(translator.translate(MODEL_PREFIX + "main_model_info", stats.bones(), stats.cubes(), stats.faces())
            .withStyle(ChatFormatting.GRAY));
        lines.add(translator.translate(MODEL_PREFIX + "texture_info", stats.textures()).withStyle(ChatFormatting.GRAY));
        return List.copyOf(lines);
    }

    private static Component detail(Translator translator, String key, String value) {
        return translator.translate(MODEL_PREFIX + key, Component.literal(value).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String errorSummary(Throwable error) {
        var visible = unwrap(error);
        return com.elfmcys.ysm.model.domain.PublicFailure.summary(visible);
    }

    private static String rootCause(Throwable error) {
        var cause = unwrap(error);
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return com.elfmcys.ysm.model.domain.PublicFailure.summary(cause);
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
            && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    public record Input(String name, String tips, List<Author> authors, String license, String path, String hash,
        @Nullable ExportInfoProto export, Stats stats, @Nullable Throwable loadError) {
        public Input {
            name = Objects.requireNonNull(name, "name");
            tips = Objects.requireNonNullElse(tips, "");
            authors = List.copyOf(authors);
            license = Objects.requireNonNullElse(license, "");
            path = Objects.requireNonNull(path, "path");
            hash = Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(stats, "stats");
        }
    }

    public record Author(String name, String role) {
        public Author {
            name = Objects.requireNonNull(name, "name");
            role = Objects.requireNonNullElse(role, "");
        }
    }

    public record Stats(int bones, int cubes, int faces, int textures) {
    }

    @FunctionalInterface
    public interface Translator {
        MutableComponent translate(String key, Object... arguments);
    }
}
