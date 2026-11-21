package com.elfmcys.ysm.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.utils.UnmodifiableConfigWrapper;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.session.server.RestrictedModelPolicy;
import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.IConfigSpec;

/**
 * Validates raw candidates before Forge correction can erase policy errors.
 *
 * <p>
 * Execution: acceptance and reload callbacks serialize on this instance. Policy reads return an immutable snapshot;
 * null means no valid candidate is available. Rejected candidates leave accepted values and policy unchanged.
 *
 * <p>
 * Seam: establishes raw candidate acceptance in [configuration
 * publication](docs/architecture/model-management/sessions/overview.md#配置与目录发布).
 */
public final class ServerConfigSpec extends UnmodifiableConfigWrapper<UnmodifiableConfig>
    implements IConfigSpec<ServerConfigSpec> {
    private final ForgeConfigSpec values;
    private CommentedConfig source;
    private volatile RestrictedModelPolicy policy;

    ServerConfigSpec(ForgeConfigSpec values) {
        super(values);
        this.values = values;
        // Other configuration consumers can use defaults while user catalog publication remains sealed.
        values.setConfig(CommentedConfig.inMemory());
    }

    @org.jetbrains.annotations.Nullable
    public RestrictedModelPolicy policy() {
        return policy;
    }

    public synchronized void setConfig(CommentedConfig candidate) {
        acceptConfig(candidate);
    }

    @Override
    public synchronized void acceptConfig(CommentedConfig candidate) {
        source = candidate;
        if (candidate == null) {
            policy = null;
            return;
        }
        afterReload();
    }

    @Override
    public synchronized void afterReload() {
        if (source == null)
            return;
        try {
            var next = RestrictedModelPolicy.parse(candidatePolicy(source));
            if (!values.isCorrect(source)) {
                values.correct(source);
                if (source instanceof FileConfig file)
                    file.save();
            }
            // The watched config mutates in place; retain a complete accepted copy for value consumers.
            values.setConfig(acceptedCopy(source));
            policy = next;
        } catch (IllegalArgumentException invalid) {
            YesSteveModel.LOGGER.error("Rejected server configuration; the previous policy remains active", invalid);
        }
    }

    private static CommentedConfig acceptedCopy(UnmodifiableConfig source) {
        var copy = CommentedConfig.copy(source);
        copy.valueMap().replaceAll((key, value) -> acceptedValue(value));
        return copy;
    }

    private static Object acceptedValue(Object value) {
        if (value instanceof UnmodifiableConfig config)
            return acceptedCopy(config);
        if (value instanceof List<?> list)
            return list.stream().map(ServerConfigSpec::acceptedValue).toList();
        return value;
    }

    private static Object candidatePolicy(CommentedConfig candidate) {
        return candidate.contains("RestrictedModelList") ? candidate.getRaw("RestrictedModelList") : List.of();
    }

    // Invalid policy must bypass the watcher correction/save step and reach afterReload unchanged.
    @Override
    public boolean isCorrect(CommentedConfig candidate) {
        try {
            RestrictedModelPolicy.parse(candidatePolicy(candidate));
            return values.isCorrect(candidate);
        } catch (IllegalArgumentException invalid) {
            return true;
        }
    }

    @Override
    public int correct(CommentedConfig candidate) {
        try {
            RestrictedModelPolicy.parse(candidatePolicy(candidate));
            return values.correct(candidate);
        } catch (IllegalArgumentException invalid) {
            return 0;
        }
    }

    @Override
    public boolean isCorrecting() {
        return values.isCorrecting();
    }
}
