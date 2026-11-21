package com.elfmcys.ysm.model.session.server;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.service.CatalogGlob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Immutable validated configuration; the server owner applies it to each newly published catalog.
 *
 * <p>
 * Seam: establishes complete policy classification for [configuration
 * publication](docs/architecture/model-management/sessions/overview.md#配置与目录发布).
 */
public final class RestrictedModelPolicy {
    private final List<Rule> rules;

    private RestrictedModelPolicy(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RestrictedModelPolicy parse(Object candidate) {
        if (!(candidate instanceof List<?> list))
            throw new IllegalArgumentException("RestrictedModelList must be a string list");
        var rules = new ArrayList<Rule>();
        for (var value : list) {
            if (!(value instanceof String text))
                throw new IllegalArgumentException("RestrictedModelList contains a non-string value");
            for (int i = 0; i < text.length(); i++) {
                var c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                        throw new IllegalArgumentException("RestrictedModelList contains invalid Unicode");
                } else if (Character.isLowSurrogate(c))
                    throw new IllegalArgumentException("RestrictedModelList contains invalid Unicode");
            }
            var path = text.startsWith("path:");
            var prefix = path ? 5 : text.startsWith("id:") ? 3 : -1;
            if (prefix < 0 || text.length() == prefix)
                throw new IllegalArgumentException("RestrictedModelList requires path:<glob> or id:<glob>");
            rules.add(new Rule(path, text.substring(prefix)));
        }
        return new RestrictedModelPolicy(rules);
    }

    public boolean restricted(String address, ModelId id) {
        return rules.stream()
            .anyMatch(rule -> CatalogGlob.matches(rule.pattern(), rule.path() ? address : id.toString()));
    }

    /** A null policy isolates user entries until the first valid configuration is accepted. */
    public static CatalogSnapshot classify(CatalogSnapshot source,
        @org.jetbrains.annotations.Nullable RestrictedModelPolicy policy) {
        var records = new LinkedHashMap<ModelId, CatalogRecord>();
        source.byModelId().forEach((id, record) -> {
            var builtin = record.location().rootKind() == CatalogRootKind.BUILTIN;
            if (!builtin && policy == null)
                return;
            var access = !builtin && policy.restricted(record.location().value(), id) ? CatalogAccess.AUTHORIZED
                : CatalogAccess.PUBLIC;
            var entry = record.entry();
            records.put(id, access == entry.access() ? record : new CatalogRecord(
                new CatalogEntry(id, entry.path(), access, entry.presentation()), record.location(), record.binding()));
        });
        return new CatalogSnapshot(
            records, source.intrinsicDefault(), source.packs().stream()
                .filter(pack -> policy != null || pack.rootKind() == CatalogRootKind.BUILTIN).toList(),
            source.report());
    }

    private record Rule(boolean path, String pattern) {
    }
}
