package com.elfmcys.ysm.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;

public class TokenBucket {
    private final LongArrayList lastRequestTime;
    private final int interval;
    private long nextTime;

    public TokenBucket(int capacity, float outputPerSecond) {
        this.lastRequestTime = new LongArrayList(capacity);
        this.interval = Math.round(1000 / outputPerSecond);
        for (int i = 0; i < capacity; i++) {
            this.lastRequestTime.add(0);
        }
    }

    public boolean request() {
        var now = System.currentTimeMillis();
        if (now < this.nextTime) {
            return false;
        }

        var lastRequestTime = this.lastRequestTime;
        lastRequestTime.removeLong(lastRequestTime.size() - 1);
        lastRequestTime.add(0, now);

        long interval = this.interval;
        long nextTime = Long.MAX_VALUE;
        for (var i = 0; i < lastRequestTime.size(); i++) {
            nextTime = Math.min(nextTime, lastRequestTime.getLong(i) + interval * (i + 1));
        }
        this.nextTime = nextTime;

        return true;
    }
}
