package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.model.ModelRuntime;
import java.util.LinkedHashMap;
import java.util.Map;

final class CatalogLinksHostProbe {
    private CatalogLinksHostProbe() {}

    static Map<String, Object> snapshot() throws Exception {
        var catalog = ModelRuntime.system().catalog();
        if (catalog.busy())
            return null;
        var facts = new LinkedHashMap<String, Object>();
        var sequence = catalog.getClass().getDeclaredField("scanSequence");
        sequence.setAccessible(true);
        synchronized (catalog) {
            facts.put("scanSequence", sequence.getLong(catalog));
        }
        facts.put("localPaths", catalog.current().byLocation().keySet().stream()
            .map(location -> location.path().value()).sorted().toList());
        facts.put("errors", catalog.current().report().errorCount());
        return facts;
    }
}
