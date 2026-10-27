package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.parser.pojo.manifest.metadata.ModelMetadata;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ConfigForms;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ExtraAnimationButton;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ExtraAnimationClassify;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ModelProperties;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.common.StringPairProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.AuthorProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigLabelProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationClassifyProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class ModelInfoBuilder {
    private ModelInfoBuilder() {}

    static @Nullable InfoProto.Builder buildInfo(boolean buildOutput, ModelManifest sourceManifest,
        ImageBlobReader imageReader, MolangFrontend frontend) throws IOException {
        var info = buildOutput ? InfoProto.newBuilder() : null;
        var properties = sourceManifest.properties;

        var settings = info == null ? null : SettingsProto.newBuilder();
        if (info != null) {
            settings.setDefaultTexture(Objects.requireNonNull(properties.defaultTexture, ""))
                .setPreviewAnimation(Objects.requireNonNull(properties.previewAnimation, ""))
                .setDisablePreviewRotation(properties.disablePreviewRotation);
            if (properties.extraAnimation.isEmpty()) {
                for (var index = 0; index < 8; index++) {
                    settings
                        .addExtraAnimation(StringPairProto.newBuilder().setKey("extra" + index).setValue("").build());
                }
            } else {
                addStringPairs(settings::addExtraAnimation, properties.extraAnimation);
            }
            for (var buttonIndex = 0; buttonIndex < properties.extraAnimationButtons.size(); buttonIndex++) {
                var button = properties.extraAnimationButtons.get(buttonIndex);
                if (button != null) {
                    settings
                        .addExtraAnimationButtons(toProto(button, frontend, "manifest/settings/button/" + buttonIndex));
                }
            }
            for (var classify : properties.extraAnimationClassify) {
                if (classify != null) {
                    settings.addExtraAnimationClassify(toProto(classify));
                }
            }
        }

        if (properties.guiForeground != null) {
            imageReader.read(properties.guiForeground, "gui-foreground", RawImageCompressor.GUI_IMAGE,
                settings == null ? null : settings::setGuiForeground);
        }
        if (properties.guiBackground != null) {
            imageReader.read(properties.guiBackground, "gui-background", RawImageCompressor.GUI_IMAGE,
                settings == null ? null : settings::setGuiBackground);
        }

        if (sourceManifest.metadata != null) {
            var metadata = info == null ? null : MetadataProto.newBuilder();
            writeMetadata(metadata, sourceManifest.metadata, imageReader);
            if (metadata != null) {
                info.setMetadata(metadata.build());
            }
        }
        if (info != null) {
            info.setSettings(Objects.requireNonNull(settings, "settings").build());
        }
        return info;
    }

    static void setProperties(InfoProto.Builder info, ModelProperties properties, String originVer, Hash256 modelHash) {
        info.setProperties(PropertiesProto.newBuilder().setOriginVer(originVer).setFree(properties.free)
            .setModelId(ByteBuffer.wrap(modelHash.bytes())).build());
    }

    static ModelSettingsProto modelSettings(ModelProperties properties) {
        return ModelSettingsProto.newBuilder().setHeightScale(properties.heightScale)
            .setWidthScale(properties.widthScale).setRenderLayersFirst(properties.renderLayersFirst)
            .setForceCulling(properties.forceCulling).setGuiNoLighting(properties.guiNoLighting)
            .setMergeMultilineExpr(properties.mergeMultilineExpr).build();
    }

    private static void writeMetadata(@Nullable MetadataProto.Builder dst, ModelMetadata src,
        ImageBlobReader imageReader) throws IOException {
        if (dst != null) {
            dst.setName(Objects.requireNonNull(src.name, "")).setTips(Objects.requireNonNull(src.tips, ""));
            dst.setLicense(LicenseProto.newBuilder()
                .setType((src.license != null && src.license.type != null) ? src.license.type : "All Rights Reserved")
                .setDesc((src.license != null && src.license.desc != null) ? src.license.desc : "").build());
        }
        if (src.authors != null) {
            for (var author : src.authors) {
                if (author == null) {
                    continue;
                }
                var proto = dst == null ? null
                    : AuthorProto.newBuilder().setName(Objects.requireNonNull(author.name, ""))
                        .setRole(Objects.requireNonNull(author.role, ""))
                        .setComment(Objects.requireNonNull(author.comment, ""));
                if (proto != null) {
                    addStringPairs(proto::addContacts, author.contact);
                }
                if (StringUtils.isNotBlank(author.avatar)) {
                    imageReader.read(author.avatar, "avatar", RawImageCompressor.AUTHOR_AVATAR,
                        proto == null ? null : proto::setAvatar);
                }
                if (proto != null) {
                    Objects.requireNonNull(dst, "dst").addAuthors(proto.build());
                }
            }
        }

        if (dst != null) {
            addStringPairs(dst::addLinks, src.link);
        }
    }

    private static ExtraAnimationButtonProto toProto(ExtraAnimationButton src, MolangFrontend frontend,
        String location) {
        var dst = ExtraAnimationButtonProto.newBuilder().setId(Objects.requireNonNull(src.id, ""))
            .setName(Objects.requireNonNull(src.name, "")).setSound(Objects.requireNonNull(src.sound, ""));
        if (src.configForms != null) {
            for (var index = 0; index < src.configForms.size(); index++) {
                var form = src.configForms.get(index);
                if (form != null) {
                    dst.addConfigForms(toProto(form, frontend, location + "/form/" + index));
                }
            }
        }
        return dst.build();
    }

    private static ConfigFormsProto toProto(ConfigForms src, MolangFrontend frontend, String location) {
        var source = Objects.requireNonNullElse(src.value, "");
        var actionOnlyRadio =
            source.isBlank() && "radio".equals(src.type) && src.labels != null && !src.labels.isEmpty();
        if (source.isBlank() && !actionOnlyRadio) {
            throw new IllegalArgumentException("Config expression is empty at " + location);
        }
        var dst = ConfigFormsProto.newBuilder().setType(Objects.requireNonNullElse(src.type, ""))
            .setTitle(Objects.requireNonNullElse(src.title, ""))
            .setDescription(Objects.requireNonNullElse(src.description, ""))
            .setReadProgram(FrontendPrograms.compile(frontend, actionOnlyRadio ? "0" : source,
                MolangFrontend.SourceMode.EXPRESSION, location + "/read"))
            .setWriteProgram(FrontendPrograms.compile(frontend, actionOnlyRadio ? "return;" : source + "=t.value",
                MolangFrontend.SourceMode.CONFIG_WRITE, location + "/write"))
            .setStep((float)src.step).setMin((float)src.min).setMax((float)src.max);
        if (src.labels != null) {
            var index = 0;
            for (var entry : src.labels.entrySet()) {
                dst.addLabels(ConfigLabelProto
                    .newBuilder().setName(entry.getKey()).setActionProgram(FrontendPrograms.compile(frontend,
                        entry.getValue(), MolangFrontend.SourceMode.INSTRUCTION, location + "/label/" + index++))
                    .build());
            }
        }
        return dst.build();
    }

    private static ExtraAnimationClassifyProto toProto(ExtraAnimationClassify src) {
        var dst = ExtraAnimationClassifyProto.newBuilder().setId(src.id);
        addStringPairs(dst::addExtraAnimation, src.extraAnimation);
        return dst.build();
    }

    private static void addStringPairs(Consumer<StringPairProto> adder, @Nullable Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (var entry : values.entrySet()) {
            adder.accept(StringPairProto.newBuilder().setKey(entry.getKey()).setValue(entry.getValue()).build());
        }
    }

    @FunctionalInterface
    interface ImageBlobReader {
        void read(String path, String hashType, RawImageCompressor.Policy policy,
            @Nullable Consumer<ImageProto> consumer) throws IOException;
    }
}
