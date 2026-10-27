package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.util.Closeable;

public interface ResourceLease extends Closeable {
    ModelResourcePurpose purpose();

    AcquireResult poll();

    boolean isCurrent(ResourceRequest request);

    /** Releases this handle's exact interest only while its Flight is Pending. */
    void cancelPending();
}
