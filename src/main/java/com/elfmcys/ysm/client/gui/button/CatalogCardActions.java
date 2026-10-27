package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Shortcut dispatch deliberately has no selection or preparation capability. */
public final class CatalogCardActions {
    private CatalogCardActions() {}

    public static boolean click(boolean control, int button, String address, ClientCatalogEntry entry,
        boolean integrated, Consumer<String> clipboard, Consumer<Path> openDirectory) {
        if (!control || button != 0 && button != 1)
            return false;
        if (button == 1)
            clipboard.accept(address);
        else if (integrated && entry != null && entry.hasLocalSource()) {
            var binding = entry.content().storageBinding();
            if (binding != null)
                binding.localSource().ifPresent(
                    source -> openDirectory.accept(source.key().sourceKind() == ModelSourceKind.CURRENT_RAW_DIRECTORY
                        ? source.logicalPath() : source.logicalPath().getParent()));
        }
        return true;
    }
}
