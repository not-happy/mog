package com.mog.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 相机：持有位置与旋转（欧拉角，单位弧度），并提供视图矩阵计算。
 */
public class Camera {

    /** x = pitch（俯仰），y = yaw（偏航），z = roll（翻滚） */
    private final Vector3f position = new Vector3f(0, 0, 0);
    private final Vector3f rotation = new Vector3f(0, 0, 0);

    public Camera() {
    }

    public Camera(Vector3f position) {
        this.position.set(position);
    }

    /** 计算视图矩阵写入 dest 并返回（避免每帧分配）。 */
    public Matrix4f getViewMatrix(Matrix4f dest) {
        return dest.identity()
                .rotateX(rotation.x)
                .rotateY(rotation.y)
                .translate(-position.x, -position.y, -position.z);
    }

    /**
     * 相机前方向量（世界空间，单位化）写入 dest 并返回。
     * 与 getViewMatrix 的 Rx(pitch)·Ry(yaw) 约定对应：
     * forward = (cos p·sin y, -sin p, -cos p·cos y)
     */
    public Vector3f getForward(Vector3f dest) {
        float cp = (float) Math.cos(rotation.x);
        return dest.set(
                cp * (float) Math.sin(rotation.y),
                (float) -Math.sin(rotation.x),
                -cp * (float) Math.cos(rotation.y));
    }

    public Vector3f getPosition() {
        return position;
    }

    public Vector3f getRotation() {
        return rotation;
    }

    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
    }

    public void movePosition(float offsetX, float offsetY, float offsetZ) {
        position.add(offsetX, offsetY, offsetZ);
    }

    public void moveRotation(float offsetX, float offsetY, float offsetZ) {
        rotation.add(offsetX, offsetY, offsetZ);
    }
}
