package com.mog.render;

import org.joml.Vector3f;

/**
 * PBR 材质数据（glTF 金属度-粗糙度工作流）：纯数据持有者。
 * albedo 基色（可被基色纹理调制）、metallic 金属度 [0,1]、roughness 粗糙度 [0,1]。
 */
public class PbrMaterial {

    private final Vector3f albedo;
    private final float metallic;
    private final float roughness;

    public PbrMaterial(Vector3f albedo, float metallic, float roughness) {
        this.albedo = albedo;
        this.metallic = metallic;
        this.roughness = roughness;
    }

    /** 默认：白色非金属、较粗糙（glTF 规范默认 metallic=1，但无键时取 0 更安全直观）。 */
    public static PbrMaterial defaults() {
        return new PbrMaterial(new Vector3f(1, 1, 1), 0f, 0.9f);
    }

    public Vector3f getAlbedo() {
        return albedo;
    }

    public float getMetallic() {
        return metallic;
    }

    public float getRoughness() {
        return roughness;
    }
}
