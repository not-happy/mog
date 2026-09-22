package com.mog.ecs.components;

import com.mog.ecs.Component;
import org.joml.Vector3f;

/**
 * 变换组件：位置 / 旋转（欧拉角，弧度）/ 缩放。纯数据。
 */
public class TransformComponent implements Component {

    private final Vector3f position = new Vector3f();
    private final Vector3f rotation = new Vector3f();
    private final Vector3f scale = new Vector3f(1, 1, 1);

    public Vector3f getPosition() {
        return position;
    }

    public Vector3f getRotation() {
        return rotation;
    }

    public Vector3f getScale() {
        return scale;
    }
}
