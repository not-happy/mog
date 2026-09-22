package com.mog.game;

import org.joml.Vector3f;

/**
 * 方向光数据：纯数据持有者。
 * direction 表示光的传播方向（世界空间），着色器中取反得到指向光源的方向。
 */
public class Light {

    private final Vector3f direction;
    private final Vector3f color;
    private final float intensity;

    public Light(Vector3f direction, Vector3f color, float intensity) {
        this.direction = direction;
        this.color = color;
        this.intensity = intensity;
    }

    /** 默认光：从左上方斜射过来的白色光。 */
    public static Light defaults() {
        return new Light(new Vector3f(-0.4f, -1.0f, -0.5f), new Vector3f(1, 1, 1), 1.0f);
    }

    public Vector3f getDirection() {
        return direction;
    }

    public Vector3f getColor() {
        return color;
    }

    public float getIntensity() {
        return intensity;
    }
}
