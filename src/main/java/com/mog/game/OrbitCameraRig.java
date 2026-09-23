package com.mog.game;

import com.mog.input.InputHandler;
import com.mog.render.Camera;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * 轨道相机装备：绕目标点环绕（左键拖拽旋转、滚轮缩放）。
 * 用于宇宙视角观赏三体运动。不要求光标捕获（拖拽式交互）。
 *
 * 球面坐标 -> Camera(position + Euler) 换算：
 *   pos = target + dist·(sinθ·cosφ, sinφ, cosθ·cosφ)
 *   匹配 getViewMatrix 的 forward 公式可得 rotation = (φ, −θ, 0)
 */
public class OrbitCameraRig implements CameraRig {

    private static final float DRAG_SENSITIVITY = 0.005f;
    private static final float MAX_PITCH = (float) Math.toRadians(85f);
    private static final float MIN_DIST = 6f;
    private static final float MAX_DIST = 400f;

    private final Camera camera;
    private final Vector3f target = new Vector3f(0, 0, 0);
    private float yaw = 0.4f;          // θ
    private float pitch = 0.6f;        // φ
    private float distance = 75f;      // 系统跨度 ±22，留出观赏距离

    public OrbitCameraRig(Camera camera) {
        this.camera = camera;
        applyToCamera();
    }

    @Override
    public void update(InputHandler input, float deltaTime, boolean mouseLook) {
        if (input.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
            yaw += (float) input.getDeltaX() * DRAG_SENSITIVITY;
            pitch += (float) input.getDeltaY() * DRAG_SENSITIVITY;
            pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
        }
        double scroll = input.consumeScrollY();
        if (Math.abs(scroll) > 1e-6) {
            distance *= (float) Math.pow(0.9, scroll);   // 滚轮上=拉近
            distance = Math.max(MIN_DIST, Math.min(MAX_DIST, distance));
        }
        applyToCamera();
    }

    private void applyToCamera() {
        float cp = (float) Math.cos(pitch);
        camera.getPosition().set(
                target.x + distance * (float) Math.sin(yaw) * cp,
                target.y + distance * (float) Math.sin(pitch),
                target.z + distance * (float) Math.cos(yaw) * cp);
        camera.getRotation().set(pitch, -yaw, 0);
    }
}
