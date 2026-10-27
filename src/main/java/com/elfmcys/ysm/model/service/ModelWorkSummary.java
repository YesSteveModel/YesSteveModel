package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import java.util.List;

/** Immutable accounting for a fixed finite catalog selection. */
public record ModelWorkSummary(int total, List<Item> items, int unprocessed, boolean stopped) {
    public ModelWorkSummary {
        items = List.copyOf(items);
        if (total < 0 || unprocessed < 0 || total != items.size() + unprocessed) {
            throw new IllegalArgumentException("Model work accounting is incomplete");
        }
    }

    public int success() {
        return count(Outcome.SUCCESS);
    }

    public int failed() {
        return count(Outcome.FAILED);
    }

    public int skipped() {
        return count(Outcome.SKIPPED);
    }

    private int count(Outcome outcome) {
        return Math.toIntExact(items.stream().filter(item -> item.outcome == outcome).count());
    }

    public enum Outcome {
        SUCCESS, FAILED, SKIPPED
    }

    public record Item(ModelFileIdentity identity, String path, Outcome outcome, String reason) {
    }
}
