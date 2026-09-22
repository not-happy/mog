package com.mog.ecs.components;

import com.mog.ecs.Component;

/**
 * 轨道组件：绕场景中心的水平圆周运动参数，由 OrbitSystem 消费。
 * angle 是可变运行状态（系统每帧推进）。
 */
public class OrbitComponent implements Component {

    private final float radius;
    private final float height;
    /** 角速度（弧度/秒） */
    private final float speed;
    private float angle;

    public OrbitComponent(float radius, float height, float speed, float initialAngle) {
        this.radius = radius;
        this.height = height;
        this.speed = speed;
        this.angle = initialAngle;
    }

    public float getRadius() {
        return radius;
    }

    public float getHeight() {
        return height;
    }

    public float getSpeed() {
        return speed;
    }

    public float getAngle() {
        return angle;
    }

    public void setAngle(float angle) {
        this.angle = angle;
    }
}
