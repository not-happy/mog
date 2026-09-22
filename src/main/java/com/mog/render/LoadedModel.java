package com.mog.render;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型加载结果：网格列表 + 材质表（经典/PBR 二选一）+ 材质贴图 + 网格到材质的映射 + 包围盒。
 * 纯数据容器，资源释放由 cleanup() 统一完成。
 */
public class LoadedModel {

    private final List<Mesh> meshes;
    /** 经典材质，按材质索引存放；PBR 材质对应的槽位为 null */
    private final List<Material> materials;
    /** PBR 材质，与 materials 对齐；经典材质对应的槽位为 null */
    private final List<PbrMaterial> pbrMaterials;
    /** 与材质表对齐，可为 null（该材质无贴图） */
    private final List<Texture> textures;
    /** 每个网格对应的材质索引 */
    private final List<Integer> meshMaterialIndex;
    /** 整体包围盒：[minX, minY, minZ, maxX, maxY, maxZ]，用于自动缩放/对齐 */
    private final float[] aabb;
    /** 骨架（无骨骼模型为 null） */
    private final Skeleton skeleton;
    /** 动画列表（可为空） */
    private final List<AnimationData> animations;

    public LoadedModel(List<Mesh> meshes, List<Material> materials, List<PbrMaterial> pbrMaterials,
                       List<Texture> textures, List<Integer> meshMaterialIndex, float[] aabb,
                       Skeleton skeleton, List<AnimationData> animations) {
        this.meshes = meshes;
        this.materials = materials;
        this.pbrMaterials = pbrMaterials;
        this.textures = textures;
        this.meshMaterialIndex = meshMaterialIndex;
        this.aabb = aabb;
        this.skeleton = skeleton;
        this.animations = animations;
    }

    public int getMeshCount() {
        return meshes.size();
    }

    public Mesh getMesh(int meshIndex) {
        return meshes.get(meshIndex);
    }

    /** 取某网格的经典材质；该网格是 PBR 材质或无材质时返回 null。 */
    public Material getMaterial(int meshIndex) {
        int mi = meshMaterialIndex.get(meshIndex);
        return (mi >= 0 && mi < materials.size()) ? materials.get(mi) : null;
    }

    /** 取某网格的 PBR 材质；非 PBR 时返回 null。 */
    public PbrMaterial getPbrMaterial(int meshIndex) {
        int mi = meshMaterialIndex.get(meshIndex);
        return (mi >= 0 && mi < pbrMaterials.size()) ? pbrMaterials.get(mi) : null;
    }

    /** 取某网格的贴图；没有则返回 null（走纯色材质渲染）。 */
    public Texture getTexture(int meshIndex) {
        int mi = meshMaterialIndex.get(meshIndex);
        return (mi >= 0 && mi < textures.size()) ? textures.get(mi) : null;
    }

    /** 包围盒：[minX, minY, minZ, maxX, maxY, maxZ]。 */
    public float[] getAabb() {
        return aabb;
    }

    /** 模型原始高度（包围盒 Y 跨度）。 */
    public float getHeight() {
        return aabb[4] - aabb[1];
    }

    /** 骨架；无骨骼模型返回 null。 */
    public Skeleton getSkeleton() {
        return skeleton;
    }

    public List<AnimationData> getAnimations() {
        return animations;
    }

    public boolean hasAnimations() {
        return animations != null && !animations.isEmpty();
    }

    public void cleanup() {
        meshes.forEach(Mesh::cleanup);
        Set<Texture> seen = new HashSet<>();
        for (Texture t : textures) {
            if (t != null && seen.add(t)) {
                t.cleanup();
            }
        }
        meshes.clear();
        materials.clear();
        pbrMaterials.clear();
        textures.clear();
        meshMaterialIndex.clear();
    }
}
