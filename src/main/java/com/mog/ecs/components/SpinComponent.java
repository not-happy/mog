package com.mog.ecs.components;

import com.mog.ecs.Component;
import org.joml.Vector3f;

/**
 * 自旋组件：三轴角速度（弧度/秒），由 SpinSystem 消费。纯数据。
 */
public class SpinComponent implements Component {

    private final Vector3f speed;

    public SpinComponent(float speedX, float speedY, float speedZ) {
        this.speed = new Vector3f(speedX, speedY, speedZ);
    }

    public Vector3f getSpeed() {
        return speed;
    }
}
