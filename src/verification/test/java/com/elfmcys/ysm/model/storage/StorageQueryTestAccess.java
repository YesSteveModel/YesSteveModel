package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.StorageResult;

public final class StorageQueryTestAccess {
    private StorageQueryTestAccess() {}

    public static <T> void publish(StorageQuery<T> query, StorageResult<T> result, Throwable failure) {
        query.publish(result, failure);
    }
}
