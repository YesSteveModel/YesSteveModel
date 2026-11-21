package com.elfmcys.ysm.model.domain;

import java.util.Objects;

/**
 * Identifies a published runtime role, independently of its source content.
 *
 * <p>
 * Seam: preserves [publication identity](docs/architecture/model-management/overview.md#身份与来源). The intrinsic value
 * requests the receiving process's required publication; the value alone grants no use.
 */
public sealed interface PublishedModelId {
    PublishedModelId INTRINSIC_DEFAULT = IntrinsicDefault.INSTANCE;

    static PublishedModelId model(ModelId hash) {
        return new Model(hash);
    }

    static PublishedModelId parse(String value) {
        return value.equals("default") ? INTRINSIC_DEFAULT : model(ModelId.parse(value));
    }

    default boolean isIntrinsicDefault() {
        return this == INTRINSIC_DEFAULT;
    }

    default Role role() {
        return isIntrinsicDefault() ? Role.INTRINSIC_DEFAULT : Role.MODEL;
    }

    enum Role {
        INTRINSIC_DEFAULT, MODEL
    }

    enum IntrinsicDefault implements PublishedModelId {
        INSTANCE;

        @Override
        public String toString() {
            return "default";
        }
    }

    record Model(ModelId hash) implements PublishedModelId {
        public Model {
            Objects.requireNonNull(hash, "hash");
        }

        @Override
        public String toString() {
            return hash.toString();
        }
    }
}
