package com.mog.ecs.systems;

import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.OrbitComponent;
import com.mog.ecs.components.TransformComponent;

/**
 * 轨道系统：推进轨道角并把实体放到圆周位置（XZ 平面，高度固定）。
 * 点光源与灯标记共用同一实体的 Transform，位置天然同步。
 */
public class OrbitSystem implements GameSystem {

    @Override
    public void update(World world, float deltaTime) {
        for (int e : world.view(TransformComponent.class, OrbitComponent.class)) {
            TransformComponent t = world.getComponent(e, TransformComponent.class);
            OrbitComponent o = world.getComponent(e, OrbitComponent.class);
            o.setAngle(o.getAngle() + o.getSpeed() * deltaTime);
            t.getPosition().set(
                    (float) Math.cos(o.getAngle()) * o.getRadius(),
                    o.getHeight(),
                    (float) Math.sin(o.getAngle()) * o.getRadius());
        }
    }
}
