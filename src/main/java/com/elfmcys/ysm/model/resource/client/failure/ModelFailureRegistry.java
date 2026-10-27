package com.elfmcys.ysm.model.resource.client.failure;

import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Process-local negative cache. A real failure remains frozen for its content version. */
public final class ModelFailureRegistry {
    private final Map<FailureKey, Throwable> failures = new ConcurrentHashMap<>();

    public ModelResourceFailureGate gate(ModelContent version, Stage stage, String resource,
        Consumer<Throwable> firstFailure) {
        var key = key(version, stage, resource);
        return new ModelResourceFailureGate() {
            @Override
            public Optional<Throwable> failure() {
                return Optional.ofNullable(failures.get(key));
            }

            @Override
            public void fail(Throwable cause) {
                if (failures.putIfAbsent(key, cause) == null) {
                    firstFailure.accept(cause);
                }
            }
        };
    }

    public void clear(ModelContent version) {
        var identity = identity(version);
        failures.keySet().removeIf(key -> key.identity().equals(identity));
    }

    public boolean hasMatching(ModelContent version, Stage stage, Predicate<String> resource) {
        var identity = identity(version);
        return failures.keySet().stream()
            .anyMatch(key -> key.identity().equals(identity) && key.stage() == stage && resource.test(key.resource()));
    }

    public void clear() {
        failures.clear();
    }

    private static FailureKey key(ModelContent content, Stage stage, String resource) {
        return new FailureKey(identity(content), stage, resource);
    }

    private static ModelFileIdentity identity(ModelContent content) {
        return Objects.requireNonNull(content, "content").representation().identity();
    }

    public enum Stage {
        TEXTURE, ANIMATION
    }

    public record FailureKey(ModelFileIdentity identity, Stage stage, String resource) {
        public FailureKey {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(resource, "resource");
        }
    }
}
