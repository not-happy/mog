package com.mog.game;

import org.joml.Vector3f;

/**
 * 点光源数据：纯数据持有者。
 * 衰减采用经典三项式：atten = 1 / (constant + linear·d + quadratic·d²)
 */
public class PointLight {

    private final Vector3f position;
    private final Vector3f color;
    private final float intensity;
    private final float constant;
    private final float linear;
    private final float quadratic;

    public PointLight(Vector3f position, Vector3f color, float intensity,
                      float constant, float linear, float quadratic) {
        this.position = position;
        this.color = color;
        this.intensity = intensity;
        this.constant = constant;
        this.linear = linear;
        this.quadratic = quadratic;
    }

    public Vector3f getPosition() {
        return position;
    }

    public Vector3f getColor() {
        return color;
    }

    public float getIntensity() {
        return intensity;
    }

    public float getConstant() {
        return constant;
    }

    public float getLinear() {
        return linear;
    }

    public float getQuadratic() {
        return quadratic;
    }
}
