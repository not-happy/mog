package com.mog.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AINodeAnim;
import org.lwjgl.assimp.AIQuatKey;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexel;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVectorKey;
import org.lwjgl.assimp.AIVertexWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;

/**
 * 模型加载器：用 Assimp 解析 obj/gltf/glb/fbx 等格式，提取：
 *   - 几何（位置/法线/UV/索引）
 *   - 材质（经典 Blinn-Phong 或 glTF PBR，含内嵌贴图）
 *   - 骨架（节点树 + 骨骼逆绑定矩阵）与动画（TRS 关键帧轨）
 *   - 蒙皮权重（每顶点 4 骨骼，超出截断）
 *
 * 注意：传入的是文件系统路径（不是 classpath），需在项目根目录运行。
 */
public final class ModelLoader {

    private static final Logger log = LoggerFactory.getLogger(ModelLoader.class);

    /** 默认后处理标志：三角化 + 合并重复顶点 + 平滑法线 + 修正朝内法线 + 翻转 UV（glTF 原点在左上） */
    private static final int DEFAULT_FLAGS = aiProcess_Triangulate
            | aiProcess_JoinIdenticalVertices
            | aiProcess_GenSmoothNormals
            | aiProcess_FixInfacingNormals
            | aiProcess_FlipUVs;

    private ModelLoader() {
    }

    /** 骨骼登记表：跨网格共享的全局骨骼列表（名字 -> 全局 boneId）。 */
    private static final class BoneTable {
        final Map<String, Integer> idByName = new HashMap<>();
        final List<String> nodeNames = new ArrayList<>();
        final List<Matrix4f> offsets = new ArrayList<>();

        int register(String nodeName, Matrix4f offset) {
            Integer existing = idByName.get(nodeName);
            if (existing != null) {
                return existing;
            }
            int id = nodeNames.size();
            idByName.put(nodeName, id);
            nodeNames.add(nodeName);
            offsets.add(offset);
            return id;
        }
    }

    public static LoadedModel load(String filePath) {
        return load(filePath, DEFAULT_FLAGS);
    }

    public static LoadedModel load(String filePath, int flags) {
        AIScene scene = aiImportFile(filePath, flags);
        if (scene == null || scene.mRootNode() == null
                || (scene.mFlags() & AI_SCENE_FLAGS_INCOMPLETE) != 0) {
            throw new IllegalStateException("模型加载失败: " + filePath
                    + (scene != null ? "" : " (" + aiGetErrorString() + ")"));
        }
        try {
            // ===== 1. 节点树 -> 骨架基础（先序遍历，父索引恒小于子索引）=====
            List<String> nodeNames = new ArrayList<>();
            List<Integer> nodeParents = new ArrayList<>();
            List<Matrix4f> nodeLocals = new ArrayList<>();
            processNode(scene.mRootNode(), -1, nodeNames, nodeParents, nodeLocals);

            // ===== 2. 网格（含蒙皮权重）=====
            BoneTable bones = new BoneTable();
            List<Mesh> meshes = new ArrayList<>();
            List<Material> materials = new ArrayList<>();
            List<PbrMaterial> pbrMaterials = new ArrayList<>();
            List<Texture> textures = new ArrayList<>();
            List<Integer> meshMaterialIndex = new ArrayList<>();
            float[] aabb = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

            PointerBuffer aiMeshes = scene.mMeshes();
            for (int i = 0; i < aiMeshes.capacity(); i++) {
                AIMesh aiMesh = AIMesh.create(aiMeshes.get(i));
                meshes.add(processMesh(aiMesh, bones));
                expandAabb(aiMesh, aabb);

                int matIdx = aiMesh.mMaterialIndex();
                while (materials.size() <= matIdx) {
                    parseMaterial(scene, materials.size(), materials, pbrMaterials, textures, false);
                }
                meshMaterialIndex.add(matIdx);
            }
            if (meshes.isEmpty()) {
                throw new IllegalStateException("模型中没有任何网格: " + filePath);
            }

            // ===== 3. 动画 =====
            List<AnimationData> animations = processAnimations(scene, nodeNames);

            // ===== 4. 骨架组装（骨骼名 -> 节点索引）=====
            Skeleton skeleton = null;
            if (!bones.nodeNames.isEmpty()) {
                Map<String, Integer> nodeIndexByName = new HashMap<>();
                for (int i = 0; i < nodeNames.size(); i++) {
                    nodeIndexByName.putIfAbsent(nodeNames.get(i), i);
                }
                int boneCount = bones.nodeNames.size();
                int[] boneNodeIndex = new int[boneCount];
                Matrix4f[] boneOffsets = new Matrix4f[boneCount];
                for (int b = 0; b < boneCount; b++) {
                    Integer ni = nodeIndexByName.get(bones.nodeNames.get(b));
                    boneNodeIndex[b] = ni != null ? ni : 0;
                    boneOffsets[b] = bones.offsets.get(b);
                }
                skeleton = new Skeleton(nodeNames.size(),
                        nodeNames.toArray(new String[0]),
                        nodeParents.stream().mapToInt(Integer::intValue).toArray(),
                        nodeLocals.toArray(new Matrix4f[0]),
                        boneCount, boneNodeIndex, boneOffsets);
                if (boneCount > 96) {
                    log.warn("模型 {} 骨骼数 {} 超过着色器上限 96，渲染将不完整", filePath, boneCount);
                }
                // 注：Assimp 可能对非 Y-up 源数据（如 COLLADA2GLTF 的 Z_UP 资产）在根节点
                // 插入校正旋转，而逆绑定矩阵保持原始——蒙皮输出即已"立正"。
                // 场景放置蒙皮模型时不要再叠加实体级校正旋转（历史事故：双重校正躺平）。
            }

            // ===== 5. 动画模型强制走 PBR 材质（蒙皮管线只提供 PBR 着色器）=====
            if (skeleton != null && !animations.isEmpty()) {
                for (int m = 0; m < materials.size(); m++) {
                    if (materials.get(m) != null && pbrMaterials.get(m) == null) {
                        Material classic = materials.get(m);
                        pbrMaterials.set(m, new PbrMaterial(classic.getColor(), 0f, 0.9f));
                        materials.set(m, null);
                    }
                }
            }

            long texCount = textures.stream().filter(t -> t != null).count();
            long pbrCount = pbrMaterials.stream().filter(m -> m != null).count();
            log.info("模型加载完成 {}: {} 网格, {} 材质(PBR {}), {} 贴图, {} 节点, {} 骨骼, {} 动画",
                    filePath, meshes.size(), materials.size(), pbrCount, texCount,
                    nodeNames.size(), bones.nodeNames.size(), animations.size());
            log.info(String.format("模型包围盒 min=(%.2f, %.2f, %.2f) max=(%.2f, %.2f, %.2f) 尺寸=(%.2f, %.2f, %.2f)",
                    aabb[0], aabb[1], aabb[2], aabb[3], aabb[4], aabb[5],
                    aabb[3] - aabb[0], aabb[4] - aabb[1], aabb[5] - aabb[2]));
            return new LoadedModel(meshes, materials, pbrMaterials, textures, meshMaterialIndex,
                    aabb, skeleton, animations);
        } finally {
            aiReleaseImport(scene);
        }
    }

    // ==================== 节点树 ====================

    /** 先序遍历节点树：登记 名字/父索引/本地变换。 */
    private static void processNode(AINode node, int parentIndex,
                                    List<String> names, List<Integer> parents, List<Matrix4f> locals) {
        int index = names.size();
        names.add(node.mName().dataString());
        parents.add(parentIndex);
        locals.add(toJoml(node.mTransformation()));
        PointerBuffer children = node.mChildren();
        if (children != null) {
            for (int i = 0; i < children.capacity(); i++) {
                processNode(AINode.create(children.get(i)), index, names, parents, locals);
            }
        }
    }

    /**
     * aiMatrix4x4 -> JOML Matrix4f。
     *
     * 坐标约定陷阱（实测验证）：
     *   - Assimp 是行主序：a1,a2,a3,a4 = 数学矩阵第 0 行（平移在第 4 列 a4,b4,c4）
     *   - JOML 16 参构造器是列主序：第 n 组参数 = 数学矩阵第 n 列
     * 因此必须按列传递：第 0 列 = (a1,b1,c1,d1)。传成行序 = 矩阵转置，
     * 平移分量会污染顶点 w 分量，蒙皮模型被拉成"通天面条"。
     */
    private static Matrix4f toJoml(AIMatrix4x4 m) {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4());
    }

    // ==================== 几何 + 蒙皮 ====================

    private static Mesh processMesh(AIMesh aiMesh, BoneTable bones) {
        float[] positions = readVectors(aiMesh.mVertices());
        float[] normals = aiMesh.mNormals() != null ? readVectors(aiMesh.mNormals()) : null;
        float[] texCoords = readTexCoords(aiMesh);
        int[] indices = readIndices(aiMesh);

        // 蒙皮权重：每顶点最多 4 骨骼（超出截断，主流资产不会超）
        int[] boneIds = null;
        float[] weights = null;
        PointerBuffer aiBones = aiMesh.mBones();
        if (aiBones != null && aiBones.capacity() > 0) {
            int nv = aiMesh.mNumVertices();
            boneIds = new int[nv * 4];
            weights = new float[nv * 4];
            int[] slots = new int[nv];
            for (int b = 0; b < aiBones.capacity(); b++) {
                AIBone bone = AIBone.create(aiBones.get(b));
                int gid = bones.register(bone.mName().dataString(), toJoml(bone.mOffsetMatrix()));
                AIVertexWeight.Buffer ws = bone.mWeights();
                for (int w = 0; w < bone.mNumWeights(); w++) {
                    AIVertexWeight vw = ws.get(w);
                    int vi = vw.mVertexId();
                    int slot = slots[vi];
                    if (slot < 4) {
                        boneIds[vi * 4 + slot] = gid;
                        weights[vi * 4 + slot] = vw.mWeight();
                        slots[vi] = slot + 1;
                    }
                }
            }
        }
        return new Mesh(positions, null, texCoords, normals, boneIds, weights, indices);
    }

    /** AIVector3D.Buffer -> float[]（xyz 展开）。 */
    private static float[] readVectors(AIVector3D.Buffer buf) {
        float[] out = new float[buf.capacity() * 3];
        for (int i = 0; i < buf.capacity(); i++) {
            AIVector3D v = buf.get(i);
            out[i * 3] = v.x();
            out[i * 3 + 1] = v.y();
            out[i * 3 + 2] = v.z();
        }
        return out;
    }

    private static float[] readTexCoords(AIMesh aiMesh) {
        AIVector3D.Buffer buf = aiMesh.mTextureCoords(0);
        if (buf == null) {
            return null;
        }
        int n = aiMesh.mNumVertices();
        float[] out = new float[n * 2];
        for (int i = 0; i < n; i++) {
            AIVector3D v = buf.get(i);
            out[i * 2] = v.x();
            out[i * 2 + 1] = v.y();
        }
        return out;
    }

    private static int[] readIndices(AIMesh aiMesh) {
        AIFace.Buffer faces = aiMesh.mFaces();
        int total = 0;
        for (int i = 0; i < faces.capacity(); i++) {
            AIFace face = faces.get(i);
            total += face.mNumIndices();
        }
        int[] out = new int[total];
        int k = 0;
        for (int i = 0; i < faces.capacity(); i++) {
            AIFace face = faces.get(i);
            IntBuffer ib = face.mIndices();
            if (ib.capacity() == 3) {
                out[k++] = ib.get(0);
                out[k++] = ib.get(1);
                out[k++] = ib.get(2);
            } else {
                for (int j = 1; j + 1 < ib.capacity(); j++) {
                    out[k++] = ib.get(0);
                    out[k++] = ib.get(j);
                    out[k++] = ib.get(j + 1);
                }
            }
        }
        return out;
    }

    private static void expandAabb(AIMesh aiMesh, float[] aabb) {
        AIVector3D.Buffer verts = aiMesh.mVertices();
        for (int i = 0; i < verts.capacity(); i++) {
            AIVector3D v = verts.get(i);
            aabb[0] = Math.min(aabb[0], v.x());
            aabb[1] = Math.min(aabb[1], v.y());
            aabb[2] = Math.min(aabb[2], v.z());
            aabb[3] = Math.max(aabb[3], v.x());
            aabb[4] = Math.max(aabb[4], v.y());
            aabb[5] = Math.max(aabb[5], v.z());
        }
    }

    // ==================== 动画 ====================

    private static List<AnimationData> processAnimations(AIScene scene, List<String> nodeNames) {
        List<AnimationData> result = new ArrayList<>();
        PointerBuffer aiAnims = scene.mAnimations();
        if (aiAnims == null) {
            return result;
        }
        Map<String, Integer> nodeIndex = new HashMap<>();
        for (int i = 0; i < nodeNames.size(); i++) {
            nodeIndex.putIfAbsent(nodeNames.get(i), i);
        }

        for (int a = 0; a < aiAnims.capacity(); a++) {
            AIAnimation aiAnim = AIAnimation.create(aiAnims.get(a));
            PointerBuffer channelsBuf = aiAnim.mChannels();
            List<AnimationData.Channel> channels = new ArrayList<>();
            if (channelsBuf == null) {
                continue;
            }
            for (int c = 0; c < channelsBuf.capacity(); c++) {
                AINodeAnim ch = AINodeAnim.create(channelsBuf.get(c));
                Integer ni = nodeIndex.get(ch.mNodeName().dataString());
                if (ni == null) {
                    continue; // 通道指向不存在的节点：跳过
                }
                channels.add(new AnimationData.Channel(ni,
                        readVectorTrack(ch.mPositionKeys(), ch.mNumPositionKeys()),
                        readVectorValues(ch.mPositionKeys(), ch.mNumPositionKeys()),
                        readQuatTimes(ch.mRotationKeys(), ch.mNumRotationKeys()),
                        readQuatValues(ch.mRotationKeys(), ch.mNumRotationKeys()),
                        readVectorTrack(ch.mScalingKeys(), ch.mNumScalingKeys()),
                        readVectorValues(ch.mScalingKeys(), ch.mNumScalingKeys())));
            }
            result.add(new AnimationData(
                    aiAnim.mName().dataString(),
                    (float) aiAnim.mDuration(),
                    (float) aiAnim.mTicksPerSecond(),
                    channels.toArray(new AnimationData.Channel[0]),
                    nodeNames.size()));
        }
        return result;
    }

    private static float[] readVectorTrack(AIVectorKey.Buffer keys, int count) {
        if (keys == null || count == 0) {
            return new float[0];
        }
        float[] times = new float[count];
        for (int i = 0; i < count; i++) {
            times[i] = (float) keys.get(i).mTime();
        }
        return times;
    }

    private static Vector3f[] readVectorValues(AIVectorKey.Buffer keys, int count) {
        if (keys == null || count == 0) {
            return new Vector3f[0];
        }
        Vector3f[] values = new Vector3f[count];
        for (int i = 0; i < count; i++) {
            AIVector3D v = keys.get(i).mValue();
            values[i] = new Vector3f(v.x(), v.y(), v.z());
        }
        return values;
    }

    private static float[] readQuatTimes(AIQuatKey.Buffer keys, int count) {
        if (keys == null || count == 0) {
            return new float[0];
        }
        float[] times = new float[count];
        for (int i = 0; i < count; i++) {
            times[i] = (float) keys.get(i).mTime();
        }
        return times;
    }

    private static Quaternionf[] readQuatValues(AIQuatKey.Buffer keys, int count) {
        if (keys == null || count == 0) {
            return new Quaternionf[0];
        }
        Quaternionf[] values = new Quaternionf[count];
        for (int i = 0; i < count; i++) {
            var q = keys.get(i).mValue();
            values[i] = new Quaternionf(q.x(), q.y(), q.z(), q.w());
        }
        return values;
    }

    // ==================== 材质 ====================

    private static void parseMaterial(AIScene scene, int index,
                                      List<Material> outMaterials,
                                      List<PbrMaterial> outPbrMaterials,
                                      List<Texture> outTextures,
                                      boolean forcePbr) {
        AIMaterial aiMat = AIMaterial.create(scene.mMaterials().get(index));

        Vector3f diffuse = new Vector3f(1, 1, 1);
        float shininess = 32f;
        float shininessStrength = 0.4f;
        float metallic = -1f;    // -1 = 键不存在
        float roughness = -1f;
        Vector3f baseColorFactor = null;

        try (var stack = stackPush()) {
            AIColor4D color = AIColor4D.malloc(stack);
            if (aiGetMaterialColor(aiMat, AI_MATKEY_COLOR_DIFFUSE, 0, 0, color) == aiReturn_SUCCESS) {
                diffuse.set(color.r(), color.g(), color.b());
            }
            if (aiGetMaterialColor(aiMat, "$mat.gltf.pbrMetallicRoughness.baseColorFactor", 0, 0, color) == aiReturn_SUCCESS
                    || aiGetMaterialColor(aiMat, AI_MATKEY_BASE_COLOR, 0, 0, color) == aiReturn_SUCCESS) {
                baseColorFactor = new Vector3f(color.r(), color.g(), color.b());
            }
            FloatBuffer f = stack.mallocFloat(1);
            IntBuffer one = stack.ints(1);
            if (aiGetMaterialFloatArray(aiMat, AI_MATKEY_SHININESS, 0, 0, f, one) == aiReturn_SUCCESS) {
                shininess = Math.max(f.get(0), 1f);
            }
            if (aiGetMaterialFloatArray(aiMat, AI_MATKEY_SHININESS_STRENGTH, 0, 0, f, one) == aiReturn_SUCCESS) {
                shininessStrength = Math.max(f.get(0), 0f);
            }
            if (aiGetMaterialFloatArray(aiMat, AI_MATKEY_METALLIC_FACTOR, 0, 0, f, one) == aiReturn_SUCCESS) {
                metallic = f.get(0);
            }
            if (aiGetMaterialFloatArray(aiMat, AI_MATKEY_ROUGHNESS_FACTOR, 0, 0, f, one) == aiReturn_SUCCESS) {
                roughness = f.get(0);
            }
        }

        Texture texture = extractBaseTexture(scene, aiMat);

        boolean isPbr = forcePbr || metallic >= 0 || roughness >= 0 || baseColorFactor != null;
        if (isPbr) {
            Vector3f alb = baseColorFactor != null ? baseColorFactor : diffuse;
            // 无键时：metallic 取 0（皮肤/织物类安全值），roughness 取规范默认 1
            outPbrMaterials.add(new PbrMaterial(alb,
                    metallic >= 0 ? metallic : 0f,
                    roughness >= 0 ? roughness : 1f));
            outMaterials.add(null);
        } else {
            outPbrMaterials.add(null);
            outMaterials.add(new Material(diffuse, shininess, shininessStrength));
        }
        outTextures.add(texture);
    }

    private static Texture extractBaseTexture(AIScene scene, AIMaterial aiMat) {
        try (var stack = stackPush()) {
            AIString path = AIString.callocStack(stack);
            aiGetMaterialTexture(aiMat, aiTextureType_BASE_COLOR, 0, path,
                    (IntBuffer) null, null, null, null, null, null);
            if (path.dataString().isEmpty()) {
                aiGetMaterialTexture(aiMat, aiTextureType_DIFFUSE, 0, path,
                        (IntBuffer) null, null, null, null, null, null);
            }
            String p = path.dataString();
            if (p.isEmpty()) {
                return null;
            }
            if (p.startsWith("*")) {
                return loadEmbeddedTexture(scene, Integer.parseInt(p.substring(1)));
            }
            return new Texture(p);
        }
    }

    private static Texture loadEmbeddedTexture(AIScene scene, int index) {
        PointerBuffer buf = scene.mTextures();
        if (buf == null || index < 0 || index >= buf.capacity()) {
            return null;
        }
        AITexture aiTex = AITexture.create(buf.get(index));
        int w = aiTex.mWidth();
        int h = aiTex.mHeight();
        if (w > 0 && h > 0) {
            return null; // 未压缩 texel 数据暂不支持
        }
        int size = Math.max(w, h);
        if (size <= 0) {
            return null;
        }
        AITexel.Buffer texels = aiTex.pcData();
        ByteBuffer compressed = memByteBuffer(texels.address(), size);
        return Texture.fromCompressedMemory(compressed, false);
    }
}
