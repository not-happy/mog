package com.mog.physics;

import org.joml.Vector3f;

/**
 * 轴对齐包围盒（AABB）：中心 + 半长。纯数据 + 几何查询（工具类职责）。
 * 世界空间 AABB 由 ColliderSystem 从 Transform（位置+缩放，忽略旋转）计算。
 */
public class Aabb {

    private final Vector3f center = new Vector3f();
    private final Vector3f halfExtent = new Vector3f();

    public void set(Vector3f center, Vector3f halfExtent) {
        this.center.set(center);
        this.halfExtent.set(halfExtent);
    }

    public Vector3f getCenter() {
        return center;
    }

    public Vector3f getHalfExtent() {
        return halfExtent;
    }

    /** AABB-AABB 重叠测试（三轴分离定理）。 */
    public boolean intersects(Aabb other) {
        return Math.abs(center.x - other.center.x) <= halfExtent.x + other.halfExtent.x
                && Math.abs(center.y - other.center.y) <= halfExtent.y + other.halfExtent.y
                && Math.abs(center.z - other.center.z) <= halfExtent.z + other.halfExtent.z;
    }

    public float minX() { return center.x - halfExtent.x; }
    public float maxX() { return center.x + halfExtent.x; }
    public float minY() { return center.y - halfExtent.y; }
    public float maxY() { return center.y + halfExtent.y; }
    public float minZ() { return center.z - halfExtent.z; }
    public float maxZ() { return center.z + halfExtent.z; }
}
