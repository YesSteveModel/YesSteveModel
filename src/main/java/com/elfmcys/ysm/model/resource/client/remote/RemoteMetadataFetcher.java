package com.elfmcys.ysm.model.resource.client.remote;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Network seam for one owned, continuous remote metadata prefix. */
@FunctionalInterface
public interface RemoteMetadataFetcher {
    CompletableFuture<Void> fetchMetadata(List<ModelFileIdentity> identities, MetadataReceiver receiver);

    @FunctionalInterface
    interface MetadataReceiver {
        void accept(ModelFileIdentity identity, UniBuffer bytes) throws IOException;
    }
}
