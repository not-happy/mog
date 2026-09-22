package com.mog.game;

import com.mog.input.InputHandler;
import com.mog.render.Camera;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 相机控制器：读取输入，驱动相机移动与视角旋转（FPS 式漫游）。
 * 行为逻辑放在这里，Camera 只持有数据。
 *
 * 鼠标视角采用业界标准管线（Quake/Minecraft/Unity 同款）：
 *   原始增量 -> 防传送钳制 -> × 灵敏度（DPI 归一化） -> 角度积分 -> pitch 钳制 + yaw 归一
 *
 * 明确不做的"过度处理"（每一项都曾是坑）：
 *   - 死区：那是给摇杆治硬件漂移的。鼠标零位无漂移，对称手抖在积分中统计性抵消；
 *     逐帧死区会"吃掉慢速输入、放行快速尖刺"，造成轻推无响应、视角反向棘轮漂移
 *   - 轴锁定/主导衰减：CAD 手势识别技术，会破坏 FPS 的对角线观察
 *   - 平滑/EMA 滤波：引入可感知延迟（"不跟手"）；原始增量直通是 FPS 的铁律
 *   - 过紧的单帧钳制：会吃掉快速甩动的真实输入，造成"大幅移动丢失距离"
 *
 * 坐标约定（与 Camera.getViewMatrix 的 Rx(pitch)·Ry(yaw)·T(-pos) 对应）：
 *   pitch 正值 = 低头，负值 = 抬头（限制 ±89° 防万向节翻转）
 *   水平前方向量 = (sin yaw, 0, -cos yaw)，水平右方向量 = (cos yaw, 0, sin yaw)
 */
public class CameraController {

    private static final Logger log = LoggerFactory.getLogger(CameraController.class);

    private static final float MOVE_SPEED = 5.0f;        // 单位/秒
    /** 简易角色控制：相机（观察者眼睛）不低于此高度，防止穿到地面以下 */
    private static final float FLOOR_Y = -0.85f;
    /** 键盘转视角速度（弧度/秒）：方向键控制俯仰/偏航，鼠标不可用或不顺手时的替代操作 */
    private static final float LOOK_SPEED = 2.2f;

    /**
     * 鼠标实际 DPI（鼠标驱动/硬件里设置的值）。
     * 修改它即可，手感参数按 REFERENCE_DPI 基准自动归一化：
     * 高 DPI 下相同物理位移产生成倍计数，灵敏度除以倍数、钳制乘以倍数。
     */
    private static final float MOUSE_DPI = 3200f;
    private static final float REFERENCE_DPI = 800f;
    private static final float DPI_SCALE = MOUSE_DPI / REFERENCE_DPI;

    // ===== 以 800 DPI 手感为基准 =====
    /** 灵敏度基准（弧度/计数）：常见 FPS 有效范围 0.0005 ~ 0.002 */
    private static final float BASE_SENSITIVITY = 0.0006f;
    /**
     * 单帧位移钳制基准（像素）：纯"防传送"兜底（失焦跳变等一次性异常），
     * 必须远大于快速甩动的真实单帧位移（3200DPI 快甩可达 1000+px/帧），
     * 否则会吃掉真实输入造成"大幅移动丢失距离"。常规跳变已由 resetMouse/失焦检测处理。
     */
    private static final float BASE_MAX_DELTA = 500f;

    // ===== 归一化后的实际生效值 =====
    private static final float MOUSE_SENSITIVITY = BASE_SENSITIVITY / DPI_SCALE;
    private static final float MAX_DELTA_PER_FRAME = BASE_MAX_DELTA * DPI_SCALE;

    /** 俯仰灵敏度比例：1.0 = 与偏航一致（业界默认）；调低可减弱横甩时的垂直过冲 */
    private static final float PITCH_FACTOR = 1.0f;
    private static final float MAX_PITCH = (float) Math.toRadians(89.0f);

    private final Camera camera;
    // 复用向量，避免每帧分配
    private final Vector3f forward = new Vector3f();
    private final Vector3f right = new Vector3f();
    private final Vector3f move = new Vector3f();

    public CameraController(Camera camera) {
        this.camera = camera;
    }

    /**
     * @param input     输入状态
     * @param deltaTime 帧耗时（秒）
     * @param mouseLook 是否启用鼠标视角（仅在鼠标被捕获时为 true）
     */
    public void update(InputHandler input, float deltaTime, boolean mouseLook) {
        updateLook(input, mouseLook);
        updateKeyboardLook(input, deltaTime);
        updateMovement(input, deltaTime);
    }

    /**
     * 方向键转视角：与鼠标视角共用同一套角度积分与钳制。
     */
    private void updateKeyboardLook(InputHandler input, float deltaTime) {
        float dYaw = 0;
        float dPitch = 0;
        if (input.isKeyPressed(GLFW_KEY_LEFT)) {
            dYaw -= LOOK_SPEED * deltaTime;
        }
        if (input.isKeyPressed(GLFW_KEY_RIGHT)) {
            dYaw += LOOK_SPEED * deltaTime;
        }
        if (input.isKeyPressed(GLFW_KEY_UP)) {
            dPitch -= LOOK_SPEED * deltaTime;   // 抬头（pitch 负值 = 抬头，与鼠标上移一致）
        }
        if (input.isKeyPressed(GLFW_KEY_DOWN)) {
            dPitch += LOOK_SPEED * deltaTime;
        }
        if (dYaw == 0 && dPitch == 0) {
            return;
        }
        Vector3f rot = camera.getRotation();
        rot.y = wrapPi(rot.y + dYaw);
        rot.x = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, rot.x + dPitch));
    }

    private void updateLook(InputHandler input, boolean mouseLook) {
        if (!mouseLook) {
            return;
        }

        // 业界标准：原始增量直达角度积分，无死区、无平滑、无轴锁（零延迟、1:1 跟手）。
        // 唯一的处理是防传送钳制；对称手抖在积分中统计性抵消。
        float rawDX = (float) input.getDeltaX();
        float rawDY = (float) input.getDeltaY();
        float dx = clampDelta(rawDX);
        float dy = clampDelta(rawDY);

        Vector3f rot = camera.getRotation();
        float prevYaw = rot.y;
        float prevPitch = rot.x;

        // 标准 FPS/MC 约定：鼠标上移 -> 抬头看天。想要反转 Y（飞行模拟式）把 += 改成 -=
        rot.y += dx * MOUSE_SENSITIVITY;
        rot.x += dy * MOUSE_SENSITIVITY * PITCH_FACTOR;

        // pitch 钳制：越过 ±90° 世界会翻转（万向节问题），所有 FPS 的标准处理
        rot.x = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, rot.x));
        // yaw 归一化到 (-π, π]：防止长时间游玩后角度无限增大导致 float 精度劣化
        rot.y = wrapPi(rot.y);

        // 鼠标诊断日志：in=原始增量 applied=钳制后增量 clamped=是否丢弃了输入
        // pitchHitLimit=是否顶到俯仰极限（顶限时继续推鼠标角度不再变化，属正常）
        if (log.isDebugEnabled()) {
            boolean clamped = (dx != rawDX) || (dy != rawDY);
            boolean pitchHitLimit = (rot.x == MAX_PITCH || rot.x == -MAX_PITCH) && dy != 0;
            log.debug("[look] in=({},{}) applied=({},{}) clamped={} | yaw {}->{} pitch {}->{} pitchHitLimit={}",
                    String.format("%.1f", rawDX), String.format("%.1f", rawDY),
                    String.format("%.1f", dx), String.format("%.1f", dy),
                    clamped,
                    String.format("%.5f", prevYaw), String.format("%.5f", rot.y),
                    String.format("%.5f", prevPitch), String.format("%.5f", rot.x),
                    pitchHitLimit);
        }
    }

    private void updateMovement(InputHandler input, float deltaTime) {
        // WASD 移动（相对朝向，限制在水平面）
        float yaw = camera.getRotation().y;
        float sinYaw = (float) Math.sin(yaw);
        float cosYaw = (float) Math.cos(yaw);
        forward.set(sinYaw, 0, -cosYaw);
        right.set(cosYaw, 0, sinYaw);

        move.set(0, 0, 0);
        if (input.isKeyPressed(GLFW_KEY_W)) {
            move.add(forward);
        }
        if (input.isKeyPressed(GLFW_KEY_S)) {
            move.sub(forward);
        }
        if (input.isKeyPressed(GLFW_KEY_D)) {
            move.add(right);
        }
        if (input.isKeyPressed(GLFW_KEY_A)) {
            move.sub(right);
        }
        if (input.isKeyPressed(GLFW_KEY_SPACE)) {
            move.y += 1;
        }
        if (input.isKeyPressed(GLFW_KEY_LEFT_SHIFT)) {
            move.y -= 1;
        }

        if (move.lengthSquared() > 1e-6f) {
            // 归一化后按速度×时间移动：斜向移动不会更快
            move.normalize().mul(MOVE_SPEED * deltaTime);
            camera.getPosition().add(move);
        }
        // 地面约束（角色控制器的最简形态：位置修正）
        if (camera.getPosition().y < FLOOR_Y) {
            camera.getPosition().y = FLOOR_Y;
        }
    }

    /** 把单帧鼠标位移钳制在 ±MAX_DELTA_PER_FRAME 内（只拦跳变，不拦正常甩动）。 */
    private static float clampDelta(float delta) {
        return Math.max(-MAX_DELTA_PER_FRAME, Math.min(MAX_DELTA_PER_FRAME, delta));
    }

    /** 角度归一化到 (-π, π]。 */
    private static float wrapPi(float angle) {
        final float twoPi = (float) (2.0 * Math.PI);
        angle %= twoPi;
        if (angle <= -twoPi / 2) {
            angle += twoPi;
        } else if (angle > twoPi / 2) {
            angle -= twoPi;
        }
        return angle;
    }
}
