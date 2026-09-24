package com.mog.game;

import com.mog.astro.Epoch;
import com.mog.astro.EpochType;
import com.mog.astro.GameCalendar;
import com.mog.core.Scene;
import com.mog.core.event.EventBus;
import com.mog.ecs.World;
import com.mog.ecs.components.BuildingComponent;
import com.mog.ecs.components.MaterialComponent;
import com.mog.ecs.components.MeshComponent;
import com.mog.ecs.components.ResourceNodeComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.ecs.systems.SurfaceViewSystem;
import com.mog.ecs.systems.TransformSystem;
import com.mog.input.InputHandler;
import com.mog.physics.ScreenPicker;
import com.mog.render.Camera;
import com.mog.render.GridRenderer;
import com.mog.render.Material;
import com.mog.render.Mesh;
import com.mog.render.PostProcessor;
import com.mog.render.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * 地表场景（C3）：45° RTS 建造视角下的行星地表——网格地形 + 建造网格 +
 * 由三体模拟实时驱动的太阳与纪元氛围（GDD D5："宇宙视角看到的三星之舞，
 * 就是地表经历的天气"）。玩家动词：左键放置建筑、Shift+左键拆除、数字键 1-5 选型。
 *
 * <p><b>视图薄壳</b>：逻辑真源是 Game 级持久的双会话——{@link CosmosSession}
 * （三体模拟：太阳方向/纪元/历法/时间控制）与 {@link SurfaceSession}（地表状态：
 * 占位/建筑/节点/库存）。本场景 init 时从会话全量重建实体，并维护私有的
 * "逻辑 id -> 实体 id" 映射（易失，随场景销毁重建——逻辑 id 跨 Tab 恒定，无悬挂）；
 * 每帧视图状态由 {@link SurfaceViewSystem} 从会话抄写。这兑现了 D5"宇宙视角时
 * 地表模拟继续运行"，也修复了旧版"建筑 Tab 往返即丢"缺陷。
 *
 * <p>红线：场景零事件——EventBus 连"发"都不做，放置/拆除/枯竭事件全部由
 * SurfaceSession 发布，订阅一律在 Game.wireEvents（EventBus 只有 clear() 没有退订，
 * 场景实例随 Tab 切换销毁重建，订阅会跨场景泄漏）。构造入参 eventBus 因此闲置，
 * 仅保持 Game 接线签名稳定。
 *
 * 拾取（handleInput 内每帧）：窗口坐标 -> NDC -> 逆 projView 射线 -> 打 y=0 平面
 * -> 命中点 -> 格。守卫链顺序不可换：inBounds 必须先于 worldToCell（钳制陷阱）。
 * 矩阵取 renderOverlay 缓存的上一帧 projection（Renderer 返回活对象，须值拷贝；
 * 首帧无缓存跳过拾取，resize 一帧自愈）。
 *
 * cleanup 只释放本场景 GL 资源（地形/网格线/方盒/节点球），绝不销毁会话。
 */
public class SurfaceScene implements Scene {

    private static final Logger log = LoggerFactory.getLogger(SurfaceScene.class);

    /** 地形配色（lit 管线 materialColor）：暗橄榄——寒曜行星的苔原基调 */
    private static final Vector3f TERRAIN_COLOR = new Vector3f(0.32f, 0.35f, 0.28f);
    /** 拾取射线平面高度与最远距离（米）：地表缓丘 ±0.25m，打 y=0 足够 */
    private static final float PICK_PLANE_Y = 0f;
    private static final float PICK_MAX_DIST = 1000f;
    /** 资源节点球配色（顶点色管线：无 MaterialComponent 即免阴影遍，1 draw/个） */
    private static final float[] NODE_COLOR_RICH = {0.85f, 0.62f, 0.30f};   // 琥珀矿脉
    private static final float[] NODE_COLOR_SPENT = {0.34f, 0.34f, 0.38f};  // 枯竭灰（墓碑）

    @SuppressWarnings("unused") // 场景零事件：发布已上收 SurfaceSession，入参仅保签名稳定
    private final EventBus eventBus;
    private final CosmosSession cosmos;
    private final SurfaceSession surface;
    private final PostProcessor post;

    private final World world = new World();
    private final Camera camera = new Camera();
    /** 太阳（方向光）：每步由 SurfaceSky 就地改写（direction/color 活引用 + setIntensity） */
    private final Light sun = new Light(new Vector3f(0, -1, 0), new Vector3f(1, 1, 1), 1f);

    private RtsCameraRig cameraRig;
    private GridRenderer grid;
    private final List<Mesh> ownedMeshes = new ArrayList<>();
    /** 已应用的纪元色调（节流：纪元类型变化才写 PostProcessor，避免每帧 setUniform 级调用） */
    private EpochType tintedEpoch;

    // ===== C3 建造视图状态 =====
    /** 逻辑占位 id -> ECS 实体 id（场景私有易失映射，init 重建；会话侧逻辑 id 跨 Tab 恒定） */
    private final Map<Integer, Integer> entityByLogical = new HashMap<>();
    /** 当前选型（数字键 1-5 切换） */
    private BuildingType selected = BuildingType.CRYO_POD;
    /** 共享立方体 Mesh（全部建筑一个 draw 单元，cleanup 统一释放） */
    private Mesh boxMesh;
    /** 光标所在格（拾取结果；cursorValid=false 表示光标不在网格上） */
    private int cursorCellX = -1;
    private int cursorCellZ = -1;
    private boolean cursorValid;
    /** 上一帧 projection 值拷贝（Renderer 返回活对象，直接持引用会被 resize 改写） */
    private final Matrix4f cachedProjection = new Matrix4f();
    private boolean projectionValid;

    // 每帧计算暂存（零分配惯例）
    private final Vector3f sunDirScratch = new Vector3f();
    private final Vector3f sunColorScratch = new Vector3f();
    private final Vector2f ndcScratch = new Vector2f();
    private final Matrix4f viewScratch = new Matrix4f();
    private final Matrix4f projViewScratch = new Matrix4f();
    private final Matrix4f invScratch = new Matrix4f();
    private final Vector3f rayOriginScratch = new Vector3f();
    private final Vector3f rayDirScratch = new Vector3f();
    private final Vector3f hitScratch = new Vector3f();
    private final Vector3f colorScratch = new Vector3f();

    public SurfaceScene(EventBus eventBus, CosmosSession cosmos,
                        SurfaceSession surface, PostProcessor post) {
        this.eventBus = eventBus;
        this.cosmos = cosmos;
        this.surface = surface;
        this.post = post;
    }

    @Override
    public void init() {
        long terrainSeed = surface.getTerrainSeed();
        float heightScale = SurfaceSession.HEIGHT_SCALE;

        // ===== 地形（lit 管线：位置 + 解析法线，颜色走材质）=====
        Mesh terrain = TerrainBuilder.build(
                BuildGrid.CELLS, BuildGrid.CELL_SIZE, heightScale, terrainSeed);
        ownedMeshes.add(terrain);
        int terrainEntity = world.createEntity("terrain");
        world.addComponent(terrainEntity, new TransformComponent());
        world.addComponent(terrainEntity, new MeshComponent(terrain));
        world.addComponent(terrainEntity,
                new MaterialComponent(new Material(new Vector3f(TERRAIN_COLOR), 8f, 0.05f)));

        // ===== 建造网格线（overlay 遍：alpha 混合贴地 + 光标高亮）=====
        grid = new GridRenderer(BuildGrid.CELLS, BuildGrid.CELL_SIZE, heightScale, terrainSeed);
        grid.init();

        // ===== 视图状态复位（场景随 Tab 销毁重建，字段态必须显式归零）=====
        entityByLogical.clear();
        selected = BuildingType.CRYO_POD;
        cursorValid = false;
        projectionValid = false;

        // ===== 从会话全量重建：建筑（共享方盒，lit 管线）=====
        boxMesh = Shapes.createCube();
        ownedMeshes.add(boxMesh);
        for (BuildingRecord b : surface.getBuildings()) {
            spawnBuildingView(b.logicalId(), b.type(), b.cellX(), b.cellZ());
        }

        // ===== 从会话全量重建：资源节点（双共享球 Mesh，顶点色管线 1 draw/个免阴影遍；
        // 位置/缩放/枯竭换 Mesh 每帧由 SurfaceViewSystem 抄会话真源）=====
        Mesh nodeRich = Shapes.createColoredSphere(10, 7,
                NODE_COLOR_RICH[0], NODE_COLOR_RICH[1], NODE_COLOR_RICH[2]);
        Mesh nodeSpent = Shapes.createColoredSphere(10, 7,
                NODE_COLOR_SPENT[0], NODE_COLOR_SPENT[1], NODE_COLOR_SPENT[2]);
        ownedMeshes.add(nodeRich);
        ownedMeshes.add(nodeSpent);
        for (ResourceNode n : surface.getNodes()) {
            int e = world.createEntity("node-" + n.getId());
            world.addComponent(e, new TransformComponent()); // 首帧即被视图系统覆写
            world.addComponent(e, new MeshComponent(n.isDepleted() ? nodeSpent : nodeRich));
            world.addComponent(e, new ResourceNodeComponent(n.getId()));
            entityByLogical.put(n.getId(), e);
        }

        // 视图同步系统先于 TransformSystem：同帧内先抄会话再算世界矩阵
        world.addSystem(new SurfaceViewSystem(surface, nodeRich, nodeSpent));
        world.addSystem(new TransformSystem());
        cameraRig = new RtsCameraRig(camera);

        log.info("地表场景就绪: 乐章种子={} 地形种子={} 建筑={} 节点={} 建造网格 {}×{}",
                cosmos.getSim().getSeed(), terrainSeed,
                surface.getBuildings().size(), surface.getNodes().size(),
                BuildGrid.CELLS, BuildGrid.CELLS);
    }

    /**
     * 建筑视图落位（占位/扣费/事件已与会话侧完成，这里只建实体 + 登记映射）：
     * 足迹最小角格 (cellX,cellZ) -> 世界中心（多格足迹取中心格点），底面贴地形。
     * 放置是低频操作，组件/Material 分配可接受；拾取路径才要求全 scratch。
     */
    private void spawnBuildingView(int logicalId, BuildingType type, int cellX, int cellZ) {
        float x = BuildGrid.cellToWorld(cellX) + (type.getFootW() - 1) * BuildGrid.CELL_SIZE / 2f;
        float z = BuildGrid.cellToWorld(cellZ) + (type.getFootD() - 1) * BuildGrid.CELL_SIZE / 2f;
        float y = TerrainBuilder.heightAt(x, z, SurfaceSession.HEIGHT_SCALE,
                surface.getTerrainSeed()) + type.getSizeY() / 2f;

        int e = world.createEntity("building-" + type.name().toLowerCase());
        TransformComponent t = new TransformComponent();
        t.getPosition().set(x, y, z);
        t.getScale().set(type.getSizeX(), type.getSizeY(), type.getSizeZ());
        world.addComponent(e, t);
        world.addComponent(e, new MeshComponent(boxMesh));
        type.getColor(colorScratch);
        world.addComponent(e, new MaterialComponent(
                new Material(new Vector3f(colorScratch), 16f, 0.3f)));
        world.addComponent(e, new BuildingComponent(type, cellX, cellZ));
        entityByLogical.put(logicalId, e);
    }

    @Override
    public void update(float deltaTime) {
        world.update(deltaTime);

        // ===== 太阳：三体模拟 -> 地表方向光（就地改写，零分配）=====
        float intensity = SurfaceSky.computeSun(cosmos, sunDirScratch, sunColorScratch);
        sun.getDirection().set(sunDirScratch);
        sun.getColor().set(sunColorScratch);
        sun.setIntensity(intensity);

        // ===== 纪元色调分级（GDD D7）：类型变化时节流写入 =====
        // tint/阴影参数是 Game 级全局态——切换复位集中在 Game.switchScene，
        // 这里只负责"在地表期间"按纪元染色
        Epoch epoch = cosmos.getCurrentEpoch();
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
            cosmos.togglePause();
        }
        if (input.isKeyJustPressed(GLFW_KEY_EQUAL)) {
            cosmos.multiplySpeed(2.0);
        }
        if (input.isKeyJustPressed(GLFW_KEY_MINUS)) {
            cosmos.multiplySpeed(0.5);
        }

        // ===== 数字键 1-5 选型（GLFW_KEY_1..5 连续，byHotkey 越界返回 null 防御）=====
        for (int i = 0; i < 5; i++) {
            if (input.isKeyJustPressed(GLFW_KEY_1 + i)) {
                BuildingType t = BuildingType.byHotkey(i + 1);
                if (t != null) {
                    selected = t;
                }
            }
        }

        // ===== 每帧拾取（先于点击判定：点击当帧高亮/格坐标已就绪）=====
        updateCursor(input);

        // ===== 点击判定（互斥，拆除优先；Shift 用持续状态查——可能先于点击按下）=====
        if (input.isMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            boolean shift = input.isKeyPressed(GLFW_KEY_LEFT_SHIFT)
                    || input.isKeyPressed(GLFW_KEY_RIGHT_SHIFT);
            if (shift) {
                tryRemove();
            } else {
                tryPlace();
            }
        }
    }

    /**
     * 光标 -> 网格拾取（守卫链，顺序不可换）。任一步失败：光标无效 + 高亮隐藏。
     * inBounds 必须先于 worldToCell——worldToCell 会钳制越界坐标（BuildLogicTest 有实锤断言）。
     */
    private void updateCursor(InputHandler input) {
        if (!projectionValid
                || !ScreenPicker.windowToNdc(input.getMouseX(), input.getMouseY(),
                        input.getWindowWidth(), input.getWindowHeight(), ndcScratch)
                || ndcScratch.x < -1f || ndcScratch.x > 1f
                || ndcScratch.y < -1f || ndcScratch.y > 1f) {
            invalidateCursor();
            return;
        }
        camera.getViewMatrix(viewScratch);
        cachedProjection.mul(viewScratch, projViewScratch);
        if (!ScreenPicker.unprojectRay(projViewScratch, invScratch,
                ndcScratch.x, ndcScratch.y, rayOriginScratch, rayDirScratch)) {
            invalidateCursor();
            return;
        }
        float t = ScreenPicker.rayPlaneY(rayOriginScratch, rayDirScratch, PICK_PLANE_Y, PICK_MAX_DIST);
        if (Float.isNaN(t)) {
            invalidateCursor();
            return;
        }
        // JOML fma(a, b, dest) = b*a + this：命中点 = origin + dir*t
        rayOriginScratch.fma(t, rayDirScratch, hitScratch);
        if (!BuildGrid.inBounds(hitScratch.x, hitScratch.z)) {
            invalidateCursor();
            return;
        }
        cursorCellX = BuildGrid.worldToCell(hitScratch.x);
        cursorCellZ = BuildGrid.worldToCell(hitScratch.z);
        cursorValid = true;
        grid.setHighlight(cursorCellX, cursorCellZ,
                surface.canPlace(cursorCellX, cursorCellZ,
                        selected.getFootW(), selected.getFootD()));
    }

    private void invalidateCursor() {
        cursorValid = false;
        grid.setHighlight(-1, -1, false);
    }

    /** 当前光标格刷新可放置性高亮（放置/拆除后立即调用，不等下一帧）。 */
    private void refreshHighlight() {
        if (cursorValid) {
            grid.setHighlight(cursorCellX, cursorCellZ,
                    surface.canPlace(cursorCellX, cursorCellZ,
                            selected.getFootW(), selected.getFootD()));
        }
    }

    /** 左键放置：会话原子序（canPlace->canAfford->扣矿->占位->事件）失败静默（红高亮已是反馈）。 */
    private void tryPlace() {
        if (!cursorValid) {
            return;
        }
        int logicalId = surface.placeBuilding(selected, cursorCellX, cursorCellZ, true);
        if (logicalId >= 0) {
            spawnBuildingView(logicalId, selected, cursorCellX, cursorCellZ);
            refreshHighlight();
        }
    }

    /** Shift+左键拆除：光标格命中任一覆盖格即可拆；资源节点不可拆（会话侧拒绝）。 */
    private void tryRemove() {
        if (!cursorValid) {
            return;
        }
        int logical = surface.logicalAt(cursorCellX, cursorCellZ);
        if (logical < 0) {
            return;
        }
        if (surface.removeBuildingAt(cursorCellX, cursorCellZ)) {
            Integer e = entityByLogical.remove(logical);
            if (e != null) {
                world.destroyEntity(e);
            }
            refreshHighlight();
        }
        // 会话拒绝（资源节点）：不动世界，静默
    }

    @Override
    public void renderOverlay(Camera camera, Matrix4f projection) {
        // 值拷贝缓存 projection 供下一帧拾取（Renderer 返回内部活对象，不可持引用）
        cachedProjection.set(projection);
        projectionValid = true;
        grid.render(camera, projection);
    }

    @Override
    public List<String> getHudLines() {
        Epoch epoch = cosmos.getCurrentEpoch();
        String epochName = epoch != null ? epoch.type().getDisplayName() : "初始化…";
        double temp = epoch != null ? epoch.temperature() : 0;
        int host = cosmos.getHostTracker().getHost();
        List<String> lines = new ArrayList<>(6);
        if (cosmos.isRunEnded()) {
            lines.add("【乐章终结: " + cosmos.getEnding().getDisplayName() + "】");
        }
        lines.add(String.format("纪元: %s   温度指数: %.3f   宿主星: %s",
                epochName, temp, host >= 0 ? "曜" + (host + 1) : "无"));
        lines.add(String.format("文明历: %s   易天: %d 次   倍速: x%.2f   %s",
                GameCalendar.format(cosmos.getSim().getTime()),
                cosmos.getHostTracker().getSwitchCount(),
                cosmos.getSpeed(),
                cosmos.isPaused() ? "[已暂停]" : ""));
        lines.add(String.format("建筑: %s (%dx%d)",
                selected.getDisplayName(), selected.getFootW(), selected.getFootD()));
        lines.add(cursorValid
                ? String.format("格坐标: %d,%d", cursorCellX, cursorCellZ)
                : "格坐标: --,--");
        lines.add("已建: " + surface.getBuildings().size());
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

    // ===== 能力声明（RTS 拖拽操作不锁光标；无准星拾取——建造走场景内网格拾取）=====

    @Override
    public boolean wantsMouseCapture() {
        return false;
    }

    @Override
    public List<String> getHintLines() {
        return List.of(
                "WASD 平移   滚轮 缩放   中键拖拽 平移   右键/QE 旋转   Tab 切换宇宙视角",
                "左键放置 · Shift+左键拆除 · 数字键1-5选择建筑",
                "F5 后处理   F6 音乐");
    }

    @Override
    public void cleanup() {
        // 只释放视图资源——会话（模拟/地表状态）跨场景存活，绝不能碰
        if (grid != null) {
            grid.cleanup();
            grid = null;
        }
        ownedMeshes.forEach(Mesh::cleanup);
        ownedMeshes.clear();
        entityByLogical.clear();
    }
}
