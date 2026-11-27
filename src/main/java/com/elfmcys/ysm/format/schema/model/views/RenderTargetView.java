package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.PhysicsDefinitionValidator;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import us.hebi.quickbuf.ProtoSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Read-only access to one independently distributable render target. */
public final class RenderTargetView {
    private final AssetFileView fileView;
    private final RenderTargetProto descriptor;
    private final Map<String, PBRTextureSetProto> textures;

    public RenderTargetView(AssetFileView fileView, RenderTargetProto descriptor) {
        this.fileView = fileView;
        this.descriptor = descriptor;
        var values = new LinkedHashMap<String, PBRTextureSetProto>();
        if (!descriptor.textures().isEmpty()) {
            descriptor.textures().forEach(values::put);
        }
        this.textures = Map.copyOf(values);
    }

    public String id() {
        return descriptor.targetId();
    }

    public RenderTargetKind kind() {
        return descriptor.kind();
    }

    public List<String> matches() {
        var values = new ArrayList<String>();
        if (!descriptor.match().isEmpty()) {
            descriptor.match().forEach(values::add);
        }
        return List.copyOf(values);
    }

    public Set<String> getTextureNames() {
        return textures.keySet();
    }

    public PBRTextureSetProto textureDescriptor(String name) throws FileNotFoundException {
        var value = textures.get(name);
        if (value == null) {
            throw new FileNotFoundException("Render target " + id() + " contains no texture named " + name);
        }
        return value;
    }

    public ModelDataProto readDefinition(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        var definition = fileView.readProtoBlob(cancelled, source, descriptor.blobId(), ModelDataProto::parseFrom);
        if (definition.hasPhysics()) {
            if (kind() != RenderTargetKind.PLAYER) {
                throw AssetLoadException.content("Render target '" + id() + "' cannot carry physics");
            }
            var main = definition.geoModels().get("main");
            if (main == null) {
                throw AssetLoadException.content("Physics requires player/main geometry");
            }
            GeoModelProto geometry;
            try {
                geometry = GeoModelProto.parseFrom(ProtoSource.newInstance(main));
            } catch (IOException error) {
                throw AssetLoadException.content("Failed to decode player/main geometry for physics", error);
            }
            PhysicsDefinitionValidator.validate(definition.physicsUnsafe(), geometry, id());
        }
        return definition;
    }

    public PBRImageSources textureSources(ChunkDataSource source, String textureName) throws IOException {
        return fileView.textureSources(source, textureDescriptor(textureName));
    }

    public PBRImageSources textureSources(BooleanSupplier cancelled, ChunkDataSource source, String textureName)
        throws IOException {
        return fileView.textureSources(cancelled, source, textureDescriptor(textureName));
    }

    public RenderTargetProto descriptor() {
        return descriptor;
    }
}
