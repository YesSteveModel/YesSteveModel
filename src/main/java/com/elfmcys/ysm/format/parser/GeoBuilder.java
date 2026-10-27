package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.model.Bone;
import com.elfmcys.ysm.format.parser.pojo.model.Cube;
import com.elfmcys.ysm.format.parser.pojo.model.ExtraInfo;
import com.elfmcys.ysm.format.parser.pojo.model.FaceUv;
import com.elfmcys.ysm.format.parser.pojo.model.GeoModel;
import com.elfmcys.ysm.format.parser.pojo.model.GeometryDescription;
import com.elfmcys.ysm.format.parser.pojo.model.UvFaces;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubeLegacyProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;
import org.joml.Math;
import org.joml.Vector2f;
import org.joml.Vector3f;

public class GeoBuilder {
    public static Result build(GeoModel raw) throws IOException {
        if (raw == null || raw.minecraftGeometry == null || raw.minecraftGeometry.isEmpty()) {
            throw new IOException("Invalid geo model");
        }
        var geometry = raw.minecraftGeometry.get(0);
        var model = GeoModelProto.newBuilder();
        var modelData = CubesProto.newBuilder();
        model.setProperties(toProto(geometry.description));

        var stats = new Stats();
        var cubeBuilder = new CubeBuilder();
        stats.bones += geometry.bones.size();
        for (var bone : geometry.bones) {
            model.addBones(toProto(bone, modelData, geometry.description, stats, cubeBuilder));
        }

        var modelDataArray = ProtoUtil.serializeToArray(modelData.build());
        model.setCubes(ByteBuffer.wrap(modelDataArray));

        var desc = geometry.description;
        return new Result(model.build(), stats, desc != null ? desc.ysmHeightScale : 0.7f,
            desc != null ? desc.ysmWidthScale : 0.7f, desc != null ? desc.ysmExtraInfo : null);
    }

    private static GeoPropertiesProto toProto(GeometryDescription src) {
        var dst = GeoPropertiesProto.newBuilder();
        if (src == null) {
            return dst.build();
        }
        dst.setTextureHeight(src.textureHeight).setTextureWidth(src.textureWidth);
        return dst.build();
    }

    private static BoneProto toProto(Bone src, CubesProto.Builder modelData, GeometryDescription properties,
        Stats stats, CubeBuilder cubeBuilder) {
        var dst = BoneProto.newBuilder().setName(src.name);
        if (src.parent != null) {
            dst.setParent(src.parent);
        }
        var pivot = arrayOrDefault(src.pivot, 0, 0, 0);
        dst.addPivot(-pivot[0]).addPivot(pivot[1]).addPivot(pivot[2]);
        var rotation = arrayOrDefault(src.rotation, 0, 0, 0);
        dst.addRotate(-Math.toRadians(rotation[0])).addRotate(-Math.toRadians(rotation[1]))
            .addRotate(Math.toRadians(rotation[2]));

        if (src.cubes != null) {
            for (var cube : src.cubes) {
                modelData
                    .addCubesLegacy(buildCube(cube, properties, src.inflate / 16f, src.mirror, stats, cubeBuilder));
            }
            dst.setCubeCount(src.cubes.size());
        }
        return dst.build();
    }

    private static CubeLegacyProto buildCube(Cube cube, GeometryDescription properties, float boneInflate,
        boolean boneMirror, Stats stats, CubeBuilder builder) {
        var dst = CubeLegacyProto.newBuilder();
        builder.clear();
        var sizeIn = arrayOrDefault(cube.size, 1, 1, 1);
        var originIn = arrayOrDefault(cube.origin, 0, 0, 0);
        var inflate = cube.inflate != null ? cube.inflate / 16f : boneInflate;

        var sx = sizeIn[0] / 16f;
        var sy = sizeIn[1] / 16f;
        var sz = sizeIn[2] / 16f;
        var ox = -(originIn[0] + sizeIn[0]) / 16f;
        var oy = originIn[1] / 16f;
        var oz = originIn[2] / 16f;

        var p1 = new Vector3f(ox - inflate, oy - inflate, oz - inflate);
        var p2 = new Vector3f(ox - inflate, oy - inflate, oz + inflate + sz);
        var p3 = new Vector3f(ox - inflate, oy + inflate + sy, oz - inflate);
        var p4 = new Vector3f(ox - inflate, oy + inflate + sy, oz + inflate + sz);
        var p5 = new Vector3f(ox + inflate + sx, oy - inflate, oz - inflate);
        var p6 = new Vector3f(ox + inflate + sx, oy - inflate, oz + inflate + sz);
        var p7 = new Vector3f(ox + inflate + sx, oy + inflate + sy, oz - inflate);
        var p8 = new Vector3f(ox + inflate + sx, oy + inflate + sy, oz + inflate + sz);

        var texWidth = properties != null && properties.textureWidth != 0 ? properties.textureWidth : 64f;
        var texHeight = properties != null && properties.textureHeight != 0 ? properties.textureHeight : 64f;
        var mirroredLayout = boneMirror || cube.mirror;
        if (cube.uv != null && cube.uv.perFaceUv != null) {
            addPerFaceQuads(builder, cube.uv.perFaceUv, cube.mirror, mirroredLayout, texWidth, texHeight, p1, p2, p3,
                p4, p5, p6, p7, p8);
        } else if (cube.uv != null && cube.uv.boxUv != null && cube.uv.boxUv.length >= 2) {
            addBoxQuads(builder, cube.uv.boxUv, sizeIn, cube.mirror, mirroredLayout, texWidth, texHeight, p1, p2, p3,
                p4, p5, p6, p7, p8);
        }

        var rotation = arrayOrDefault(cube.rotation, 0, 0, 0);
        var pivot = arrayOrDefault(cube.pivot, 0, 0, 0);
        builder.applyTransform(rotation, pivot, dst);
        dst.setFaceCount(builder.faceCount);
        stats.cubes++;
        stats.faces += builder.faceCount;
        return dst.build();
    }

    private static void addPerFaceQuads(CubeBuilder builder, UvFaces faces, boolean cubeMirror, boolean mirroredLayout,
        float texWidth, float texHeight, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, Vector3f p5, Vector3f p6,
        Vector3f p7, Vector3f p8) {
        if (!mirroredLayout) {
            addFace(builder, faces.west, cubeMirror, Direction.WEST, texWidth, texHeight, p4, p3, p1, p2);
            addFace(builder, faces.east, cubeMirror, Direction.EAST, texWidth, texHeight, p7, p8, p6, p5);
            addFace(builder, faces.north, cubeMirror, Direction.NORTH, texWidth, texHeight, p3, p7, p5, p1);
            addFace(builder, faces.south, cubeMirror, Direction.SOUTH, texWidth, texHeight, p8, p4, p2, p6);
            addFace(builder, faces.up, cubeMirror, Direction.UP, texWidth, texHeight, p4, p8, p7, p3);
            addFace(builder, faces.down, cubeMirror, Direction.DOWN, texWidth, texHeight, p1, p5, p6, p2);
        } else {
            addFace(builder, faces.west, cubeMirror, Direction.WEST, texWidth, texHeight, p7, p8, p6, p5);
            addFace(builder, faces.east, cubeMirror, Direction.EAST, texWidth, texHeight, p4, p3, p1, p2);
            addFace(builder, faces.north, cubeMirror, Direction.NORTH, texWidth, texHeight, p3, p7, p5, p1);
            addFace(builder, faces.south, cubeMirror, Direction.SOUTH, texWidth, texHeight, p8, p4, p2, p6);
            addFace(builder, faces.up, cubeMirror, Direction.UP, texWidth, texHeight, p1, p5, p6, p2);
            addFace(builder, faces.down, cubeMirror, Direction.DOWN, texWidth, texHeight, p4, p8, p7, p3);
        }
    }

    private static void addBoxQuads(CubeBuilder builder, float[] uv, float[] size, boolean cubeMirror,
        boolean mirroredLayout, float texWidth, float texHeight, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4,
        Vector3f p5, Vector3f p6, Vector3f p7, Vector3f p8) {
        var x = Math.floor(size[0]);
        var y = Math.floor(size[1]);
        var z = Math.floor(size[2]);
        var u = uv[0];
        var v = uv[1];
        if (!mirroredLayout) {
            builder.addQuad(new Vector3f[] {p4, p3, p1, p2}, u + z + x, v + z, z, y, texWidth, texHeight, cubeMirror,
                Direction.WEST);
            builder.addQuad(new Vector3f[] {p7, p8, p6, p5}, u, v + z, z, y, texWidth, texHeight, cubeMirror,
                Direction.EAST);
            builder.addQuad(new Vector3f[] {p3, p7, p5, p1}, u + z, v + z, x, y, texWidth, texHeight, cubeMirror,
                Direction.NORTH);
            builder.addQuad(new Vector3f[] {p8, p4, p2, p6}, u + z + x + z, v + z, x, y, texWidth, texHeight,
                cubeMirror, Direction.SOUTH);
            builder.addQuad(new Vector3f[] {p4, p8, p7, p3}, u + z, v, x, z, texWidth, texHeight, cubeMirror,
                Direction.UP);
            builder.addQuad(new Vector3f[] {p1, p5, p6, p2}, u + z + x, v + z, x, -z, texWidth, texHeight, cubeMirror,
                Direction.DOWN);
        } else {
            builder.addQuad(new Vector3f[] {p7, p8, p6, p5}, u + z + x, v + z, z, y, texWidth, texHeight, cubeMirror,
                Direction.WEST);
            builder.addQuad(new Vector3f[] {p4, p3, p1, p2}, u, v + z, z, y, texWidth, texHeight, cubeMirror,
                Direction.EAST);
            builder.addQuad(new Vector3f[] {p3, p7, p5, p1}, u + z, v + z, x, y, texWidth, texHeight, cubeMirror,
                Direction.NORTH);
            builder.addQuad(new Vector3f[] {p8, p4, p2, p6}, u + z + x + z, v + z, x, y, texWidth, texHeight,
                cubeMirror, Direction.SOUTH);
            builder.addQuad(new Vector3f[] {p4, p8, p7, p3}, u + z, v, x, z, texWidth, texHeight, cubeMirror,
                Direction.UP);
            builder.addQuad(new Vector3f[] {p1, p5, p6, p2}, u + z + x, v + z, x, -z, texWidth, texHeight, cubeMirror,
                Direction.DOWN);
        }
    }

    private static void addFace(CubeBuilder builder, FaceUv face, boolean cubeMirror, Direction direction,
        float texWidth, float texHeight, Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        if (face == null || face.uv == null || face.uvSize == null || face.uv.length < 2 || face.uvSize.length < 2) {
            return;
        }
        builder.addQuad(new Vector3f[] {a, b, c, d}, face.uv[0], face.uv[1], face.uvSize[0], face.uvSize[1], texWidth,
            texHeight, cubeMirror, direction);
    }

    private static float[] arrayOrDefault(float[] values, float x, float y, float z) {
        if (values == null || values.length < 3) {
            return new float[] {x, y, z};
        }
        return values;
    }

    public static final class Result {
        final GeoModelProto model;
        final Stats stats;
        final float heightScale;
        final float widthScale;
        @Nullable
        final ExtraInfo legacyInfo;

        private Result(GeoModelProto model, Stats stats, float heightScale, float widthScale,
            @Nullable ExtraInfo legacyInfo) {
            this.model = model;
            this.stats = stats;
            this.heightScale = heightScale;
            this.widthScale = widthScale;
            this.legacyInfo = legacyInfo;
        }
    }

    public static final class Stats {
        int bones;
        int cubes;
        int faces;

        ModelStatsProto toProto() {
            return ModelStatsProto.newBuilder().setBones(bones).setCubes(cubes).setFaces(faces).build();
        }
    }

    private enum Direction {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        final float x;
        final float y;
        final float z;

        Direction(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vector3f createNormal(boolean mirror) {
            return new Vector3f(mirror ? -x : x, y, z);
        }
    }

    private static final class CubeBuilder {
        private final Object2IntOpenHashMap<Vector3f> positions = new Object2IntOpenHashMap<>();
        private final Object2IntOpenHashMap<Vector2f> uvs = new Object2IntOpenHashMap<>();
        private final ArrayList<Vector3f> normals = new ArrayList<>();
        private final IntArrayList positionIndices = new IntArrayList();
        private final IntArrayList uvIndices = new IntArrayList();
        private int faceCount;

        private void clear() {
            positions.clear();
            uvs.clear();
            normals.clear();
            positionIndices.clear();
            uvIndices.clear();
            faceCount = 0;
        }

        private void addQuad(Vector3f[] vertices, float u1, float v1, float uSize, float vSize, float texWidth,
            float texHeight, boolean mirror, Direction direction) {
            var u2 = u1 + uSize;
            var v2 = v1 + vSize;
            u1 /= texWidth;
            u2 /= texWidth;
            v1 /= texHeight;
            v2 /= texHeight;

            var uv = mirror
                ? new Vector2f[] {new Vector2f(u1, v1), new Vector2f(u2, v1), new Vector2f(u2, v2),
                    new Vector2f(u1, v2)}
                : new Vector2f[] {new Vector2f(u2, v1), new Vector2f(u1, v1), new Vector2f(u1, v2),
                    new Vector2f(u2, v2)};
            normals.add(direction.createNormal(mirror));
            for (var i = 0; i < 4; i++) {
                var positionIndex = positions.computeIfAbsent(vertices[i], p -> positions.size());
                positionIndices.add(positionIndex);

                var uvIndex = uvs.computeIfAbsent(uv[i], p -> uvs.size());
                uvIndices.add(uvIndex);
            }
            faceCount++;
        }

        private void applyTransform(float[] rotation, float[] pivotIn, CubeLegacyProto.Builder proto) {
            if (positions.isEmpty()) {
                return;
            }
            proto.addAllPosIndices(positionIndices);
            proto.addAllUvIndices(uvIndices);
            var pivot = new Vector3f(-pivotIn[0] / 16f, pivotIn[1] / 16f, pivotIn[2] / 16f);
            var rx = -Math.toRadians(rotation[0]);
            var ry = -Math.toRadians(rotation[1]);
            var rz = Math.toRadians(rotation[2]);

            var posArray = new float[positions.size() * 3];
            Object2IntMaps.fastForEach(positions, entry -> {
                var pos = entry.getKey();
                rotateAround(pos, pivot, rx, ry, rz);
                var offset = entry.getIntValue() * 3;
                posArray[offset] = pos.x;
                posArray[offset + 1] = pos.y;
                posArray[offset + 2] = pos.z;
            });
            proto.addPos(posArray);

            var uvArray = new float[uvs.size() * 2];
            Object2IntMaps.fastForEach(uvs, entry -> {
                var uv = entry.getKey();
                var offset = entry.getIntValue() * 2;
                uvArray[offset] = uv.x;
                uvArray[offset + 1] = uv.y;
            });
            proto.addUv(uvArray);

            for (Vector3f normal : normals) {
                rotateVector(normal, rx, ry, rz);
                proto.addNormal(normal.x, normal.y, normal.z);
            }
        }

        private static void rotateAround(Vector3f point, Vector3f pivot, float rx, float ry, float rz) {
            var shifted = new Vector3f(point.x - pivot.x, point.y - pivot.y, point.z - pivot.z);
            rotateVector(shifted, rx, ry, rz);
            point.set(shifted.x + pivot.x, shifted.y + pivot.y, shifted.z + pivot.z);
        }

        private static void rotateVector(Vector3f point, float rx, float ry, float rz) {
            var x1 = point.x;
            var y1 = point.y * Math.cos(rx) - point.z * Math.sin(rx);
            var z1 = point.y * Math.sin(rx) + point.z * Math.cos(rx);

            var x2 = x1 * Math.cos(ry) + z1 * Math.sin(ry);
            var y2 = y1;
            var z2 = -x1 * Math.sin(ry) + z1 * Math.cos(ry);

            var x3 = x2 * Math.cos(rz) - y2 * Math.sin(rz);
            var y3 = x2 * Math.sin(rz) + y2 * Math.cos(rz);
            point.set(x3, y3, z2);
        }
    }
}
