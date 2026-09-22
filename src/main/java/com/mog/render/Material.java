package com.mog.render;

import org.joml.Vector3f;

/**
 * 材质数据：纯数据持有者，供光照着色器使用。
 */
public class Material {

    /** 漫反射基色（无纹理时直接作为物体颜色；有纹理时作为叠加色） */
    private final Vector3f color;
    /** 高光指数：越大高光越锐利 */
    private final float specularPower;
    /** 高光强度 */
    private final float specularStrength;

    public Material(Vector3f color, float specularPower, float specularStrength) {
        this.color = color;
        this.specularPower = specularPower;
        this.specularStrength = specularStrength;
    }

    /** 默认材质：白色、中等高光。 */
    public static Material defaults() {
        return new Material(new Vector3f(1, 1, 1), 32f, 0.5f);
    }

    public Vector3f getColor() {
        return color;
    }

    public float getSpecularPower() {
        return specularPower;
    }

    public float getSpecularStrength() {
        return specularStrength;
    }
}
