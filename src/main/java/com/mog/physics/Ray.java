package com.mog.physics;

import org.joml.Vector3f;

/**
 * 射线：原点 + 单位方向。用于拾取（屏幕中心 -> 世界）与将来的弹道判定。
 */
public class Ray {

    private final Vector3f origin = new Vector3f();
    private final Vector3f direction = new Vector3f();

    public void set(Vector3f origin, Vector3f direction) {
        this.origin.set(origin);
        this.direction.set(direction).normalize();
    }

    public Vector3f getOrigin() {
        return origin;
    }

    public Vector3f getDirection() {
        return direction;
    }

    /**
     * Slab 法射线-AABB 求交。
     * @return 命中距离 t（>=0），未命中返回 -1
     */
    public float intersectAabb(Aabb box) {
        float tmin = Float.NEGATIVE_INFINITY;
        float tmax = Float.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            float o = get(origin, axis);
            float d = get(direction, axis);
            float lo = getMin(box, axis);
            float hi = getMax(box, axis);
            if (Math.abs(d) < 1e-8f) {
                // 射线平行于该轴：原点必须在 slab 内
                if (o < lo || o > hi) {
                    return -1;
                }
            } else {
                float t1 = (lo - o) / d;
                float t2 = (hi - o) / d;
                if (t1 > t2) {
                    float tmp = t1; t1 = t2; t2 = tmp;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) {
                    return -1;
                }
            }
        }
        return tmax < 0 ? -1 : Math.max(tmin, 0);
    }

    private static float get(Vector3f v, int axis) {
        return axis == 0 ? v.x : (axis == 1 ? v.y : v.z);
    }

    private static float getMin(Aabb b, int axis) {
        return axis == 0 ? b.minX() : (axis == 1 ? b.minY() : b.minZ());
    }

    private static float getMax(Aabb b, int axis) {
        return axis == 0 ? b.maxX() : (axis == 1 ? b.maxY() : b.maxZ());
    }
}
