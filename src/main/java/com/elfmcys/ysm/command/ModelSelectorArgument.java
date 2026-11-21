package com.elfmcys.ysm.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.List;

/**
 * Reads one complete selector, including unquoted canonical identity punctuation.
 *
 * <p>
 * Seam: decodes command boundaries for [command
 * selection](docs/architecture/model-management/sessions/overview.md#新使用与选择资格).
 */
public final class ModelSelectorArgument implements ArgumentType<String> {
    public static ModelSelectorArgument selector() {
        return new ModelSelectorArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && reader.peek() == '"')
            return reader.readQuotedString();
        var start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek()))
            reader.skip();
        if (start == reader.getCursor())
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader,
                "selector");
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("global:pack/a.mxc", "id:author/model@1.0.0+pc", "default");
    }
}
