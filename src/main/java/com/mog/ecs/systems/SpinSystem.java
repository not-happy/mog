package com.mog.ecs.systems;

import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.SpinComponent;
import com.mog.ecs.components.TransformComponent;

/**
 * 自旋系统：按角速度 × dt 推进所有 Transform+Spin 实体的旋转。
 */
public class SpinSystem implements GameSystem {

    @Override
    public void update(World world, float deltaTime) {
        for (int e : world.view(TransformComponent.class, SpinComponent.class)) {
            TransformComponent t = world.getComponent(e, TransformComponent.class);
            SpinComponent s = world.getComponent(e, SpinComponent.class);
            t.getRotation().add(
                    s.getSpeed().x * deltaTime,
                    s.getSpeed().y * deltaTime,
                    s.getSpeed().z * deltaTime);
        }
    }
}
