package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;

import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.catalog.client.ModelPackInfo;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.client.entry.ClientFailedCatalogEntry;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelPath;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

final class CatalogBrowserState {
    private static final String AUTHOR_SEARCH_PREFIX = "@";
    private static final String PACK_SEARCH_PREFIX = "#";
    private static final Object2IntMap<String> PAGE_BY_PACK = new Object2IntOpenHashMap<>();
    private static String currentPack = "";

    private final Map<String, ModelPackInfo> allPacks = new LinkedHashMap<>();
    private ClientCatalogSnapshot catalog;
    private List<ClientCatalogEntry> models = List.of();
    private List<ClientFailedCatalogEntry> failed = List.of();
    private List<ModelPackInfo> packs = List.of();
    private Category category = Category.ALL;
    private int maxPage;

    void rebuild(ClientCatalogSnapshot next, Function<ModelPackDescriptor, ModelPackInfo> packFactory) {
        catalog = next;
        allPacks.clear();
        next.packs().forEach(pack -> allPacks.putIfAbsent(pack.hierarchy(), packFactory.apply(pack)));
        next.models().values().forEach(entry -> addSyntheticPacks(parent(entry.displayPath())));
        next.failed().forEach(entry -> addSyntheticPacks(parent(entry.displayPath())));
        if (!currentPack.isBlank() && !allPacks.containsKey(currentPack)) {
            currentPack = "";
        }
    }

    void filter(String query, String locale, Set<String> hiddenPaths, Predicate<Hash256> authorized,
        Predicate<Hash256> starred) {
        var allModels = new ArrayList<>(catalog.models().values());
        var search = query.strip().toLowerCase(Locale.ENGLISH);
        allModels.removeIf(entry -> !isVisible(entry, search, locale, hiddenPaths, authorized, starred));
        allModels.sort(Comparator.comparing(ClientCatalogEntry::displayPath));
        models = List.copyOf(allModels);

        var allFailed = new ArrayList<>(catalog.failed());
        allFailed.removeIf(entry -> !isVisible(entry, search, hiddenPaths));
        allFailed.sort(Comparator.comparing(ClientFailedCatalogEntry::displayPath));
        failed = List.copyOf(allFailed);

        if (category != Category.ALL || (!search.isBlank() && !search.startsWith(PACK_SEARCH_PREFIX))) {
            packs = List.of();
        } else {
            var packSearch = search.startsWith(PACK_SEARCH_PREFIX) ? search.substring(1) : search;
            packs = allPacks.values().stream()
                .filter(
                    pack -> search.isBlank() ? directChild(currentPack, pack.hierarchy()) : matches(pack, packSearch))
                .sorted(Comparator.comparing(ModelPackInfo::hierarchy)).toList();
        }
        maxPage = Math.max(0, (models.size() + failed.size() + packs.size() - 1) / 10);
        if (page() > maxPage) {
            resetPage();
        }
    }

    private boolean isVisible(ClientFailedCatalogEntry entry, String search, Set<String> hiddenPaths) {
        if (hiddenPaths.contains(entry.displayPath()) || category == Category.STAR) {
            return false;
        }
        if (category == Category.AUTH && entry.access() != CatalogAccess.AUTHORIZED) {
            return false;
        }
        if (search.isBlank()) {
            return category != Category.ALL || parent(entry.displayPath()).equals(currentPack);
        }
        return !search.startsWith(PACK_SEARCH_PREFIX) && !search.startsWith(AUTHOR_SEARCH_PREFIX)
            && entry.displayPath().toLowerCase(Locale.ENGLISH).contains(search);
    }

    private boolean isVisible(ClientCatalogEntry entry, String search, String locale, Set<String> hiddenPaths,
        Predicate<Hash256> authorized, Predicate<Hash256> starred) {
        var metadata = CatalogModelMetadata.from(entry);
        if (hiddenPaths.contains(metadata.path())) {
            return false;
        }
        if (category == Category.AUTH && entry.authorizationRequired() && !authorized.test(entry.modelHash())) {
            return false;
        }
        if (category == Category.STAR && !starred.test(entry.modelHash())) {
            return false;
        }
        if (search.isBlank()) {
            return category != Category.ALL || parent(entry.displayPath()).equals(currentPack);
        }
        return matches(metadata, search, locale);
    }

    private static boolean matches(CatalogModelMetadata metadata, String search, String locale) {
        if (search.startsWith(PACK_SEARCH_PREFIX)) {
            return false;
        }
        var info = metadata.info();
        var modelMetadata = info.getMetadata();
        if (search.startsWith(AUTHOR_SEARCH_PREFIX)) {
            if (modelMetadata == null) {
                return false;
            }
            var authorSearch = search.substring(1);
            for (var index = 0; index < modelMetadata.authors().size(); index++) {
                var author = modelMetadata.authors().get(index);
                if (metadata.localized(locale, "metadata.authors.%d.name".formatted(index), author.name())
                    .toLowerCase(Locale.ENGLISH).contains(authorSearch)) {
                    return true;
                }
            }
            return false;
        }
        if (metadata.path().toLowerCase(Locale.ENGLISH).contains(search)) {
            return true;
        }
        if (modelMetadata == null) {
            return false;
        }
        if (metadata.localized(locale, "metadata.name", modelMetadata.name()).toLowerCase(Locale.ENGLISH)
            .contains(search)
            || metadata.localized(locale, "metadata.tips", modelMetadata.tips().orElse("")).toLowerCase(Locale.ENGLISH)
                .contains(search)) {
            return true;
        }
        for (var index = 0; index < modelMetadata.authors().size(); index++) {
            var author = modelMetadata.authors().get(index);
            if (metadata.localized(locale, "metadata.authors.%d.name".formatted(index), author.name())
                .toLowerCase(Locale.ENGLISH).contains(search)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(ModelPackInfo pack, String search) {
        return pack.hierarchy().toLowerCase(Locale.ENGLISH).contains(search)
            || LanguageManager.getI18n(pack, "name", pack.name()).toLowerCase(Locale.ENGLISH).contains(search)
            || LanguageManager.getI18n(pack, "description", pack.desc()).toLowerCase(Locale.ENGLISH).contains(search);
    }

    ClientCatalogSnapshot catalog() {
        return catalog;
    }

    List<ClientCatalogEntry> models() {
        return models;
    }

    List<ClientFailedCatalogEntry> failed() {
        return failed;
    }

    List<ModelPackInfo> packs() {
        return packs;
    }

    Iterable<ModelPackInfo> allPacks() {
        return allPacks.values();
    }

    ModelPackInfo pack(String hierarchy) {
        return allPacks.get(hierarchy);
    }

    String currentPack() {
        return currentPack;
    }

    void enterPack(String hierarchy) {
        currentPack = hierarchy;
        resetPage();
    }

    void backToParent() {
        var previous = currentPack;
        currentPack = parent(currentPack);
        PAGE_BY_PACK.removeInt(previous);
    }

    Category category() {
        return category;
    }

    void category(Category category) {
        this.category = category;
        resetPage();
    }

    int page() {
        return PAGE_BY_PACK.getOrDefault(currentPack, 0);
    }

    void page(int page) {
        PAGE_BY_PACK.put(currentPack, page);
    }

    void resetPage() {
        page(0);
    }

    int maxPage() {
        return maxPage;
    }

    private void addSyntheticPacks(String hierarchy) {
        while (!hierarchy.isEmpty()) {
            var location = com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(hierarchy);
            if (location.entry().isEmpty())
                return;
            var slash = location.entry().lastIndexOf('/');
            var name = location.entry().substring(slash + 1);
            allPacks.putIfAbsent(hierarchy, new ModelPackInfo(hierarchy, name, "", null, Map.of()));
            hierarchy = parent(hierarchy);
        }
    }

    private String parent(String address) {
        var location = com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(address);
        if (location.entry().isEmpty())
            return "";
        var parent = location.parent();
        return parent.equals(location.root().prefix()) && !allPacks.containsKey(parent) ? "" : parent;
    }

    private boolean directChild(String parent, String candidate) {
        return !parent.equals(candidate) && parent(candidate).equals(parent);
    }

    enum Category {
        ALL, AUTH, STAR
    }
}
