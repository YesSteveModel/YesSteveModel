package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import com.elfmcys.ysm.network.protocol.RoamingProjection;
import com.elfmcys.ysm.network.protocol.RoamingValues;
import java.util.Objects;

/** A player owner replaces this aggregate before publishing any selection facts. */
public final class CommittedSelection {
    private final PublishedModelId model;
    private final long intentId;
    private final Object producer;
    private final RoamingDictionary dictionary;
    private final RoamingProjection roaming;
    private String texture;
    private String animation;

    public CommittedSelection(PublishedModelId model, long intentId, Object producer, RoamingDictionary dictionary,
        RoamingValues initial, String texture, String animation) {
        if (intentId < 0 || intentId == 0 && (producer != null || dictionary.size() != 0))
            throw new IllegalArgumentException("Invalid selection producer");
        this.model = Objects.requireNonNull(model);
        this.intentId = intentId;
        this.producer = producer;
        this.dictionary = Objects.requireNonNull(dictionary);
        this.roaming = new RoamingProjection(dictionary, initial);
        this.texture = Objects.requireNonNull(texture);
        this.animation = Objects.requireNonNull(animation);
    }

    public static CommittedSelection appearance(PublishedModelId model, String texture) {
        return new CommittedSelection(model, 0, null, new RoamingDictionary(new String[0]),
            new RoamingValues(new short[0], new float[0]), texture, "");
    }

    public PublishedModelId model() {
        return model;
    }

    public long intentId() {
        return intentId;
    }

    public RoamingDictionary dictionary() {
        return dictionary;
    }

    public RoamingProjection roaming() {
        return roaming;
    }

    public String texture() {
        return texture;
    }

    public String animation() {
        return animation;
    }

    public void texture(String value) {
        texture = Objects.requireNonNull(value);
    }

    public void animation(String value) {
        animation = Objects.requireNonNull(value);
    }

    public boolean accepts(Object source, long intent) {
        return intent > 0 && intent == intentId && producer == source;
    }

    public Selection selection() {
        return model instanceof PublishedModelId.Model selected ? new Selection.Model(selected.hash(), texture)
            : new Selection.IntrinsicDefault();
    }
}
