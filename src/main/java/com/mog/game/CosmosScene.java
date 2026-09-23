package com.mog.game;

import com.mog.astro.GameCalendar;
import com.mog.astro.GravitySimulation;
import com.mog.core.Scene;
import com.mog.core.event.EventBus;
import com.mog.ecs.World;
import com.mog.ecs.components.MeshComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.input.InputHandler;
import com.mog.render.Camera;
import com.mog.render.Mesh;
import com.mog.render.Shapes;
import com.mog.render.TrailRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

/**
 * 宇宙场景（C1 星系模拟核心）：观赏穿越家族混沌三重星之舞与行星的命运。
 *
 * 构成：三体积分模拟（CosmosSimSystem，游戏默认构型 ratio=4.0/A_IN=10）
 * + HDR 发光恒星（顶点色 >1 触发泛光）+ 彗尾式运动轨迹拖尾（TrailRenderer）
 * + 轨道环绕相机（OrbitCameraRig）+ 易天/终局事件（HostTracker/FateJudge 驱动）。
 *
 * 操作：左键拖拽旋转 / 滚轮缩放 / 空格暂停 / +- 倍速 / Tab 返回地表。
 */
public class CosmosScene implements Scene {

    private static final Logger log = LoggerFactory.getLogger(CosmosScene.class);

    /** 恒星显示颜色（>1 的 HDR 值会被泛光阈值捕获，产生辉光） */
    private static final float[][] STAR_COLORS = {
            {3.4f, 2.9f, 1.9f},   // 曜一 金黄
            {3.0f, 1.7f, 0.9f},   // 曜二 橙红
            {1.9f, 2.3f, 3.4f},   // 曜三 蓝白
    };
    private static final float[] PLANET_COLOR = {0.25f, 0.5f, 0.85f};
    /** 显示尺寸（模拟中是质点）：游戏尺度 A_IN=10 下相机拉远至 ~170，
     *  显示直径同步放大以保持与旧尺度相当的视觉占比 */
    private static final float STAR_SCALE = 4.5f;
    private static final float PLANET_SCALE = 1.2f;

    private final EventBus eventBus;
    private final World world = new World();
    private final Camera camera = new Camera();
    /** 宇宙场景不需要方向光（全顶点色管线），给渲染器一个占位 */
    private final Light placeholderLight = new Light(
            new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1), 0f);

    private GravitySimulation sim;
    private CosmosSimSystem simSystem;
    private TrailRenderer trails;
    private OrbitCameraRig cameraRig;
    private final List<Mesh> ownedMeshes = new ArrayList<>();
    /** 调试加速：初始倍速（<=0 表示用系统默认 0.5），来自启动参数 --speed N */
    private final double initialSpeed;

    public CosmosScene(EventBus eventBus) {
        this(eventBus, 0);
    }

    public CosmosScene(EventBus eventBus, double initialSpeed) {
        this.eventBus = eventBus;
        this.initialSpeed = initialSpeed;
    }

    @Override
    public void init() {
        sim = new GravitySimulation(new Random().nextLong());

        trails = new TrailRenderer(4, CosmosSimSystem.TRAIL_CAP);  // 3 恒星 + 行星 运动轨迹拖尾
        trails.init();

        // ===== 三颗恒星（HDR 发光球体：亮度 ∝ 质光关系 L=m^3.5，半径 ∝ √m 显示夸张）=====
        int[] starEntities = new int[3];
        for (int i = 0; i < 3; i++) {
            float[] c = STAR_COLORS[i];
            double lum = sim.getLuminosity(i);
            Mesh sphere = Shapes.createColoredSphere(32, 20,
                    (float) (c[0] * lum), (float) (c[1] * lum), (float) (c[2] * lum));
            ownedMeshes.add(sphere);
            int e = world.createEntity("star-" + i);
            TransformComponent t = new TransformComponent();
            float sc = STAR_SCALE * (float) Math.sqrt(sim.getMass(i));
            t.getScale().set(sc, sc, sc);
            world.addComponent(e, t);
            world.addComponent(e, new MeshComponent(sphere));
            starEntities[i] = e;
        }

        // ===== 行星 =====
        Mesh planetMesh = Shapes.createColoredSphere(24, 16,
                PLANET_COLOR[0], PLANET_COLOR[1], PLANET_COLOR[2]);
        ownedMeshes.add(planetMesh);
        int planet = world.createEntity("planet");
        TransformComponent pt = new TransformComponent();
        pt.getScale().set(PLANET_SCALE, PLANET_SCALE, PLANET_SCALE);
        world.addComponent(planet, pt);
        world.addComponent(planet, new MeshComponent(planetMesh));

        simSystem = new CosmosSimSystem(sim, starEntities, planet, trails, eventBus);
        if (initialSpeed > 0) {
            simSystem.setSpeed(initialSpeed);
            log.info("调试加速: 初始倍速 x{} (--speed)", simSystem.getSpeed());
        }
        world.addSystem(simSystem);

        cameraRig = new OrbitCameraRig(camera);
        log.info("宇宙场景就绪: 三体模拟种子={} (穿越家族混沌三重星: ratio=4.0, a_in=10, a_out=40; "
                        + "实测局长中位 196 年≈41 分钟, 终局=行星死亡 52%/恒星弹射 48%)",
                sim.getSeed());
    }

    @Override
    public void update(float deltaTime) {
        world.update(deltaTime);
    }

    @Override
    public void handleInput(InputHandler input) {
        if (input.isKeyJustPressed(GLFW_KEY_SPACE)) {
            simSystem.togglePause();
        }
        if (input.isKeyJustPressed(GLFW_KEY_EQUAL)) {
            simSystem.multiplySpeed(2.0);
        }
        if (input.isKeyJustPressed(GLFW_KEY_MINUS)) {
            simSystem.multiplySpeed(0.5);
        }
    }

    @Override
    public void renderOverlay(Camera camera, Matrix4f projection) {
        trails.render(camera, projection);
    }

    @Override
    public List<String> getHudLines() {
        var epoch = simSystem.getCurrentEpoch();
        String epochName = epoch != null ? epoch.type().getDisplayName() : "初始化…";
        double temp = epoch != null ? epoch.temperature() : 0;
        var ht = simSystem.getHostTracker();
        String endingMark = simSystem.isRunEnded()
                ? "   【乐章终结: " + simSystem.getEnding().getDisplayName() + "】"
                : "";
        return List.of(
                String.format("纪元: %s   温度指数: %.3f   %s%s", epochName, temp,
                        simSystem.isPaused() ? "[已暂停]" : "", endingMark),
                String.format("文明历: %s   宿主星: 曜%d   易天: %d 次   倍速: x%.2f",
                        GameCalendar.format(sim.getTime()), ht.getHost() + 1,
                        ht.getSwitchCount(), simSystem.getSpeed()),
                String.format("种子: %d   空格 暂停   +/- 倍速   左键拖拽 旋转   滚轮 缩放   Tab 返回地表",
                        sim.getSeed()));
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Camera getCamera() {
        return camera;
    }

    @Override
    public Light getLight() {
        return placeholderLight;
    }

    @Override
    public CameraRig getCameraRig() {
        return cameraRig;
    }

    @Override
    public void cleanup() {
        if (trails != null) {
            trails.cleanup();
        }
        ownedMeshes.forEach(Mesh::cleanup);
        ownedMeshes.clear();
    }
}
