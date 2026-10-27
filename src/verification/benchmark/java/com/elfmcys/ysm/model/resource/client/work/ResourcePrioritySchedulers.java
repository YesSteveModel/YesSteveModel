package com.elfmcys.ysm.model.resource.client.work;

import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;

/** Controlled queue comparison; single FIFO has two active workers and one unused dedicated worker. */
public final class ResourcePrioritySchedulers {
    private ResourcePrioritySchedulers() {}

    public static ClientResourceScheduler create(String mode) {
        return switch (mode) {
            case "single_fifo_control" -> new ClientResourceScheduler(3);
            case "split_equal" -> new ClientResourceScheduler(2, (ignored, body) -> ThreadScheduling
                .entry(ThreadRole.CLIENT_BACKGROUND_RESOURCE, ThreadPolicy.BELOW_NORMAL, body));
            case "split_normal" -> new ClientResourceScheduler(2);
            default -> throw new IllegalArgumentException("Unknown scheduler comparison: " + mode);
        };
    }
}
