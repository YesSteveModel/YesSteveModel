package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.model.domain.Hash256;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Closed, transport-independent set of asset selectors understood by every model source. */
public sealed interface ModelAssetSelector permits ModelAssetSelector.ModelPreview, ModelAssetSelector.RenderTarget, ModelAssetSelector.ModelPresentation, ModelAssetSelector.PackCover {
    ModelAssetKind kind();

    static ModelAssetSelector preview() {
        return new ModelPreview();
    }

    static ModelAssetSelector renderTarget(String renderTargetId, String textureName,
        Set<RenderTargetComponent> components) {
        return new RenderTarget(renderTargetId, textureName, components);
    }

    static ModelAssetSelector presentation(PresentationAsset asset, int index) {
        return new ModelPresentation(asset, index);
    }

    static ModelAssetSelector packCover(Hash256 expectedContentHash) {
        return new PackCover(expectedContentHash);
    }

    record ModelPreview() implements ModelAssetSelector {
        @Override
        public ModelAssetKind kind() {
            return ModelAssetKind.MODEL_PREVIEW;
        }
    }

    record RenderTarget(String renderTargetId, String textureName,
        Set<RenderTargetComponent> components) implements ModelAssetSelector {
        public RenderTarget {
            renderTargetId = normalize(renderTargetId, "renderTargetId");
            textureName = Objects.requireNonNullElse(textureName, "").trim();
            components = components.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(components));
            if (components.isEmpty()) {
                throw new IllegalArgumentException("Render target components must not be empty");
            }
            if (components.contains(RenderTargetComponent.TEXTURE_SET) && textureName.isEmpty()) {
                throw new IllegalArgumentException("Texture-set requests require a texture name");
            }
        }

        @Override
        public ModelAssetKind kind() {
            return ModelAssetKind.RENDER_TARGET;
        }
    }

    record ModelPresentation(PresentationAsset asset, int index) implements ModelAssetSelector {
        public ModelPresentation {
            Objects.requireNonNull(asset, "asset");
            if (index < 0) {
                throw new IllegalArgumentException("Presentation index must not be negative");
            }
        }

        @Override
        public ModelAssetKind kind() {
            return ModelAssetKind.MODEL_PRESENTATION;
        }
    }

    record PackCover(Hash256 expectedContentHash) implements ModelAssetSelector {
        public PackCover {
            Objects.requireNonNull(expectedContentHash, "expectedContentHash");
        }

        @Override
        public ModelAssetKind kind() {
            return ModelAssetKind.PACK_COVER;
        }
    }

    enum RenderTargetComponent {
        DEFINITION, TEXTURE_SET, COMMON_BEHAVIOR
    }

    enum PresentationAsset {
        MODEL_ICON, AUTHOR_AVATAR, GUI_FOREGROUND, GUI_BACKGROUND
    }

    private static String normalize(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return normalized;
    }
}
