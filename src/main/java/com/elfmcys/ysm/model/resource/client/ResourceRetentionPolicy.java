package com.elfmcys.ysm.model.resource.client;

/** Shared idle-delay policy; each resource keeps independent activity and retirement state. */
public final class ResourceRetentionPolicy {
    public static final int DEFAULT_DELAY_TICKS = 10 * 20;

    private ResourceRetentionPolicy() {}
}
