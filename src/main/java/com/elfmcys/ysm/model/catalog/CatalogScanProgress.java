package com.elfmcys.ysm.model.catalog;

/**
 * One immutable observation of an admitted local scan, independent of published content.
 * A total of -1 preserves the unknown denominator before discovery completes.
 */
public record CatalogScanProgress(long scanId, Phase phase, int processed, int total) {
    public enum Phase {
        DISCOVERING, PROCESSING, FINALIZING
    }
}
