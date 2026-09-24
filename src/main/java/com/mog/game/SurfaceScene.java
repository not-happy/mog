package com.mog.game;

import com.mog.astro.Epoch;
import com.mog.astro.EpochType;
import com.mog.astro.GameCalendar;
import com.mog.core.Scene;
import com.mog.core.event.EventBus;
import com.mog.ecs.World;
import com.mog.ecs.components.MaterialComponent;
import com.mog.ecs.components.MeshComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.ecs.systems.TransformSystem;
import com.mog.input.InputHandler;
import com.mog.render.Camera;
import com.mog.render.GridRenderer;
import com.mog.render.Material;
import com.mog.render.Mesh;
import com.mog.render.PostProcessor;
import com.mog.render.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

/**
 * 地表场景（C2 骨架）：45° RTS 建造视角下的行星地表——网格地形 + 占位建筑 +
 * 由三体模拟实时驱动的太阳与纪元氛围（GDD D5："宇宙视角看到的三星之舞，
 * 就是地表经历的天气"）。
 *
 * 模拟真源是 Game 级持久的 {@link CosmosSession}：本场景只读取（太阳方向/纪元/历法），
 * 时间控制（空格暂停、+- 倍速）也作用于同一会话——地表照常控时。
 *
 * 红线：场景不得订阅 EventBus——EventBus 只有 clear() 没有退订，
 * 场景实例随 Tab 切换销毁重建，订阅会跨场景泄漏（构造入参 eventBus 仅为
 * C3+ 发布建造/结算事件预留，本场景不订不发）。
 *
 * cleanup 只释放本场景 GL 资源（地形/网格线/方盒），绝不销毁会话。
 */
public class SurfaceScene implements Scene {

    private static final Logger log = LoggerFactory.getLogger(SurfaceScene.class);

    /** 地形起伏幅度（米）：缓丘陵，不遮挡 RTS 视线 */
    private static final float HEIGHT_SCALE = 0.25f;
    /** 地形配色（lit 管线 materialColor）：暗橄榄——寒曜行星的苔原基调 */
    private static final Vector3f TERRAIN_COLOR = new Vector3f(0.32f, 0.35f, 0.28f);

    private final EventBus eventBus;
    private final CosmosSession session;
    private final PostProcessor post;

    private final World world = new World();
    private final Camera camera = new Camera();
    /** 太阳（方向光）：每步由 SurfaceSky 就地改写（direction/color 活引用 + setIntensity） */
    private final Light sun = new Light(new Vector3f(0, -1, 0), new Vector3f(1, 1, 1), 1f);

    private RtsCameraRig cameraRig;
    private GridRenderer grid;
    private final List<Mesh> ownedMeshes = new ArrayList<>();
    private long terrainSeed;
    /** 已应用的纪元色调（节流：纪元类型变化才写 PostProcessor，避免每帧 setUniform 级调用） */
    private EpochType tintedEpoch;

    // 每帧计算暂存（零分配惯例）
    private final Vector3f sunDirScratch = new Vector3f();
    private final Vector3f sunColorScratch = new Vector3f();

    public SurfaceScene(EventBus eventBus, CosmosSession session, PostProcessor post) {
        this.eventBus = eventBus;
        this.session = session;
        this.post = post;
    }

    @Override
    public void init() {
        // 地形种子从乐章种子派生：每个乐章的地表独一无二，但同一乐章内切场景回来完全一致
        terrainSeed = session.getSim().getSeed() * 31 + 7;

        // ===== 地形（lit 管线：位置 + 解析法线，颜色走材质）=====
        Mesh terrain = TerrainBuilder.build(
                BuildGrid.CELLS, BuildGrid.CELL_SIZE, HEIGHT_SCALE, terrainSeed);
        ownedMeshes.add(terrain);
        int terrainEntity = world.createEntity("terrain");
        world.addComponent(terrainEntity, new TransformComponent());
        world.addComponent(terrainEntity, new MeshComponent(terrain));
        world.addComponent(terrainEntity,
                new MaterialComponent(new Material(new Vector3f(TERRAIN_COLOR), 8f, 0.05f)));

        // ===== 建造网格线（overlay 遍：alpha 混合贴地）=====
        grid = new GridRenderer(BuildGrid.CELLS, BuildGrid.CELL_SIZE, HEIGHT_SCALE, terrainSeed);
        grid.init();

        // ===== 占位建筑（C3 建造核心接入后由玩家落位替换）=====
        Mesh box = Shapes.createCube();
        ownedMeshes.add(box);
        placeBox(box, "cryo-pod-1", 14, 14, 1.6f, 1.0f, 1.6f, new Vector3f(0.42f, 0.55f, 0.62f));
        placeBox(box, "cryo-pod-2", 17, 15, 1.6f, 1.0f, 1.6f, new Vector3f(0.42f, 0.55f, 0.62f));
        placeBox(box, "cryo-pod-3", 15, 18, 1.6f, 1.0f, 1.6f, new Vector3f(0.42f, 0.55f, 0.62f));
        placeBox(box, "command-hub", 19, 17, 2.4f, 2.0f, 2.4f, new Vector3f(0.72f, 0.60f, 0.40f));

        world.addSystem(new TransformSystem());
        cameraRig = new RtsCameraRig(camera);

        log.info("地表场景就绪: 乐章种子={} 地形种子={} 建造网格 {}×{} (格 {}m, 半宽 {}m)",
                session.getSim().getSeed(), terrainSeed,
                BuildGrid.CELLS, BuildGrid.CELLS, BuildGrid.CELL_SIZE, BuildGrid.HALF_EXTENT);
    }

    /** 占位方盒落位：吸附格中心、贴地形高度（底面落在 heightAt 上）。 */
    private void placeBox(Mesh box, String name, int cellX, int cellZ,
                          float sx, float sy, float sz, Vector3f color) {
        float x = BuildGrid.cellToWorld(cellX);
        float z = BuildGrid.cellToWorld(cellZ);
        float y = TerrainBuilder.heightAt(x, z, HEIGHT_SCALE, terrainSeed) + sy / 2f;
        int e = world.createEntity(name);
        TransformComponent t = new TransformComponent();
        t.getPosition().set(x, y, z);
        t.getScale().set(sx, sy, sz);
        world.addComponent(e, t);
        world.addComponent(e, new MeshComponent(box));
        world.addComponent(e, new MaterialComponent(new Material(color, 16f, 0.3f)));
    }

    @Override
    public void update(float deltaTime) {
        world.update(deltaTime);

        // ===== 太阳：三体模拟 -> 地表方向光（就地改写，零分配）=====
        float intensity = SurfaceSky.computeSun(session, sunDirScratch, sunColorScratch);
        sun.getDirection().set(sunDirScratch);
        sun.getColor().set(sunColorScratch);
        sun.setIntensity(intensity);

        // ===== 纪元色调分级（GDD D7）：类型变化时节流写入 =====
        // tint/阴影参数是 Game 级全局态——切换复位集中在 Game.switchScene，
        // 这里只负责"在地表期间"按纪元染色
        Epoch epoch = session.getCurrentEpoch();
        EpochType type = epoch != null ? epoch.type() : null;
        if (type != tintedEpoch) {
            tintedEpoch = type;
            if (type != null) {
                EpochPalette palette = EpochPalette.of(type);
                float[] tint = palette.getTintColor();
                post.setTint(tint[0], tint[1], tint[2], palette.getTintStrength());
            }
        }
    }

    @Override
    public void handleInput(InputHandler input) {
        // 与宇宙场景共享同一会话的时间控制：地表也能暂停/倍速
        if (input.isKeyJustPressed(GLFW_KEY_SPACE)) {
            session.togglePause();
        }
        if (input.isKeyJustPressed(GLFW_KEY_EQUAL)) {
            session.multiplySpeed(2.0);
        }
        if (input.isKeyJustPressed(GLFW_KEY_MINUS)) {
            session.multiplySpeed(0.5);
        }
    }

    @Override
    public void renderOverlay(Camera camera, Matrix4f projection) {
        grid.render(camera, projection);
    }

    @Override
    public List<String> getHudLines() {
        Epoch epoch = session.getCurrentEpoch();
        String epochName = epoch != null ? epoch.type().getDisplayName() : "初始化…";
        double temp = epoch != null ? epoch.temperature() : 0;
        int host = session.getHostTracker().getHost();
        List<String> lines = new ArrayList<>(3);
        if (session.isRunEnded()) {
            lines.add("【乐章终结: " + session.getEnding().getDisplayName() + "】");
        }
        lines.add(String.format("纪元: %s   温度指数: %.3f   宿主星: %s",
                epochName, temp, host >= 0 ? "曜" + (host + 1) : "无"));
        lines.add(String.format("文明历: %s   易天: %d 次   倍速: x%.2f   %s",
                GameCalendar.format(session.getSim().getTime()),
                session.getHostTracker().getSwitchCount(),
                session.getSpeed(),
                session.isPaused() ? "[已暂停]" : ""));
        return lines;
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
        return sun;
    }

    @Override
    public CameraRig getCameraRig() {
        return cameraRig;
    }

    // ===== 能力声明（RTS 拖拽操作不锁光标；无准星拾取/存读档——C3 再评估）=====

    @Override
    public boolean wantsMouseCapture() {
        return false;
    }

    @Override
    public List<String> getHintLines() {
        return List.of(
                "WASD 平移   滚轮 缩放   中键拖拽 平移   右键/QE 旋转   Tab 切换宇宙视角",
                "F5 后处理   F6 音乐");
    }

    @Override
    public void cleanup() {
        // 只释放视图资源——会话（模拟/乐章状态）跨场景存活，绝不能碰
        if (grid != null) {
            grid.cleanup();
            grid = null;
        }
        ownedMeshes.forEach(Mesh::cleanup);
        ownedMeshes.clear();
    }
}
