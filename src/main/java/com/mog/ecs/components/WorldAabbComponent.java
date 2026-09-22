package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.physics.Aabb;

/**
 * 世界空间 AABB 缓存：由 CollisionSystem 每帧刷新，射线拾取等直接读取。
 * 计算结果缓存进组件是 ECS 惯例（避免每处消费方重复计算）。
 */
public class WorldAabbComponent implements Component {

    private final Aabb aabb = new Aabb();

    public Aabb getAabb() {
        return aabb;
    }
}
