package com.mog.game;

import com.mog.input.InputHandler;
import com.mog.render.Camera;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * RTS 相机装备（地表建造视角）：固定俯角 45°，焦点 + 高度 + 偏航三自由度。
 *
 * 操作：WASD 平移（速度随高度缩放）/ 滚轮缩放高度 / 中键拖拽平移 /
 * 右键拖拽或 Q/E 旋转偏航。焦点指数平滑（系数 12）——输入先到 desired，
 * 相机追平，手感绵密不晕。不要求光标捕获（与 OrbitCameraRig 同惯例，
 * mouseLook 参数忽略）。
 *
 * {@link #getFocus()} 为 C3 建造拾取预留（屏幕坐标 -> 焦点平面射线落点）。
 */
public class RtsCameraRig implements CameraRig {

    /** 固定俯角：45° 俯视（经典 RTS 建造视角） */
    private static final float PITCH = (float) Math.toRadians(45f);
    private static final float MIN_HEIGHT = 8f;
    private static final float MAX_HEIGHT = 80f;
    /** WASD 平移速度（米/秒，基准高度下） */
    private static final float PAN_SPEED = 18f;
    /** 平移/拖拽速度的基准高度：实际速度随 height/基准 缩放（拉远移动更快） */
    private static final float PAN_HEIGHT_REF = 32f;
    /** 中键拖拽灵敏度（世界米/像素，基准高度下） */
    private static final float DRAG_PAN_PIXEL = 0.01f;
    /** 右键拖拽旋转灵敏度（弧度/像素） */
    private static final float DRAG_YAW_SENS = 0.005f;
    /** Q/E 键盘旋转速度（弧度/秒） */
    private static final float YAW_SPEED = 1.8f;
    /** 焦点指数平滑系数（1/s） */
    private static final float SMOOTH = 12f;
    /** 焦点钳制范围（略大于网格半宽 32m，允许站边缘向外看） */
    private static final float MAX_FOCUS = 40f;

    private final Camera camera;
    /** 平滑后的实际焦点（相机跟踪此值） */
    private final Vector3f focus = new Vector3f();
    /** 输入请求的目标焦点（平滑插值的另一端） */
    private final Vector3f desired = new Vector3f();
    private float height = 34f;
    private float yaw = 0f;

    public RtsCameraRig(Camera camera) {
        this.camera = camera;
        applyToCamera();
    }

    @Override
    public void update(InputHandler input, float deltaTime, boolean mouseLook) {
        float sinY = (float) Math.sin(yaw);
        float cosY = (float) Math.cos(yaw);
        // 水平前向（相机视线在 XZ 平面的投影）与右向
        float fwdX = -sinY;
        float fwdZ = -cosY;
        float rightX = cosY;
        float rightZ = -sinY;

        // ===== WASD 平移（速度随高度缩放）=====
        float speed = PAN_SPEED * deltaTime * (height / PAN_HEIGHT_REF);
        if (input.isKeyPressed(GLFW_KEY_W)) {
            desired.x += fwdX * speed;
            desired.z += fwdZ * speed;
        }
        if (input.isKeyPressed(GLFW_KEY_S)) {
            desired.x -= fwdX * speed;
            desired.z -= fwdZ * speed;
        }
        if (input.isKeyPressed(GLFW_KEY_D)) {
            desired.x += rightX * speed;
            desired.z += rightZ * speed;
        }
        if (input.isKeyPressed(GLFW_KEY_A)) {
            desired.x -= rightX * speed;
            desired.z -= rightZ * speed;
        }

        // ===== 中键拖拽平移（拖地图：焦点反向跟随鼠标）=====
        if (input.isMouseButtonDown(GLFW_MOUSE_BUTTON_MIDDLE)) {
            float k = DRAG_PAN_PIXEL * (height / PAN_HEIGHT_REF);
            desired.x -= (rightX * (float) input.getDeltaX() - fwdX * (float) input.getDeltaY()) * k;
            desired.z -= (rightZ * (float) input.getDeltaX() - fwdZ * (float) input.getDeltaY()) * k;
        }

        // ===== 滚轮缩放高度 =====
        double scroll = input.consumeScrollY();
        if (Math.abs(scroll) > 1e-6) {
            height *= (float) Math.pow(0.85, scroll);   // 滚轮上 = 压低 = 拉近
            height = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height));
        }

        // ===== 右键拖拽 / QE 旋转偏航 =====
        if (input.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
            yaw += (float) input.getDeltaX() * DRAG_YAW_SENS;
        }
        if (input.isKeyPressed(GLFW_KEY_Q)) {
            yaw += YAW_SPEED * deltaTime;
        }
        if (input.isKeyPressed(GLFW_KEY_E)) {
            yaw -= YAW_SPEED * deltaTime;
        }

        // ===== 焦点钳制 + 指数平滑 =====
        desired.x = Math.max(-MAX_FOCUS, Math.min(MAX_FOCUS, desired.x));
        desired.z = Math.max(-MAX_FOCUS, Math.min(MAX_FOCUS, desired.z));
        float t = 1f - (float) Math.exp(-SMOOTH * deltaTime);
        focus.x += (desired.x - focus.x) * t;
        focus.z += (desired.z - focus.z) * t;

        applyToCamera();
    }

    /** 球面坐标 -> Camera：与 OrbitCameraRig 同款公式，俯角固定 45°。
     *  rotation = (φ, −θ, 0) 匹配 getViewMatrix 的 forward 约定。 */
    private void applyToCamera() {
        float cp = (float) Math.cos(PITCH);
        float sp = (float) Math.sin(PITCH);
        camera.getPosition().set(
                focus.x + height * (float) Math.sin(yaw) * cp,
                focus.y + height * sp,
                focus.z + height * (float) Math.cos(yaw) * cp);
        camera.getRotation().set(PITCH, -yaw, 0);
    }

    /** 当前平滑焦点（活引用，C3 建造拾取预留；调用方只读）。 */
    public Vector3f getFocus() {
        return focus;
    }

    public float getHeight() {
        return height;
    }

    public float getYaw() {
        return yaw;
    }
}
