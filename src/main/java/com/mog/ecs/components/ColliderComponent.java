package com.mog.ecs.components;

import com.mog.ecs.Component;
import org.joml.Vector3f;

/**
 * 碰撞体组件：本地空间 AABB 半长（纯数据）。
 * 世界半长 = 本地半长 × Transform 缩放（旋转暂不参与，AABB 近似）。
 * 注意：单位为模型本地单位——归一化缩放后的模型（如鸭子 0.0097）要用模型原始尺寸填。
 */
public class ColliderComponent implements Component {

    private final Vector3f halfExtent = new Vector3f();

    public ColliderComponent(float hx, float hy, float hz) {
        halfExtent.set(hx, hy, hz);
    }

    public Vector3f getHalfExtent() {
        return halfExtent;
    }
}
