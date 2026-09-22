package com.mog.game;

import com.mog.asset.AssetManager;
import com.mog.core.Scene;
import com.mog.core.event.EventBus;
import com.mog.ecs.World;
import com.mog.ecs.components.AnimationComponent;
import com.mog.ecs.components.ColliderComponent;
import com.mog.ecs.components.EmitterComponent;
import com.mog.ecs.components.MaterialComponent;
import com.mog.ecs.components.MeshComponent;
import com.mog.ecs.components.OrbitComponent;
import com.mog.ecs.components.ParentComponent;
import com.mog.ecs.components.PbrMaterialComponent;
import com.mog.ecs.components.PointLightComponent;
import com.mog.ecs.components.SpinComponent;
import com.mog.ecs.components.TextureComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.ecs.systems.AnimationSystem;
import com.mog.ecs.systems.CollisionSystem;
import com.mog.ecs.systems.OrbitSystem;
import com.mog.ecs.systems.SpinSystem;
import com.mog.ecs.systems.TransformSystem;
import com.mog.render.Camera;
import com.mog.render.LoadedModel;
import com.mog.render.Material;
import com.mog.render.Mesh;
import com.mog.render.ModelLoader;
import com.mog.render.ParticleEngine;
import com.mog.render.PbrMaterial;
import com.mog.render.Shapes;
import com.mog.render.Texture;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示场景（ECS 版）：构建 World——黄昏氛围，草地地面、旋转立方体、
 * 散布立方体、归一化鸭子模型、三盏带自发光标记的巡游彩灯。
 *
 * 职责：创建实体并挂载组件、注册系统、持有资源所有权（cleanup）。
 * 行为逻辑全部在 System（SpinSystem/OrbitSystem）中，本类不含每帧逻辑。
 */
public class DemoScene implements Scene {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoScene.class);

    private static final float GROUND_Y = -1.0f;
    /** 模型资源路径（相对项目根目录，需在根目录运行） */
    private static final String DUCK_MODEL = "assets/models/duck.glb";
    private static final String DANCER_MODEL = "assets/models/cesiumman.glb";
    /** 草地纹理（classpath）；同图不同环绕模式通过 ?repeat 参数区分资产 */
    private static final String GRASS_TEXTURE = "/textures/sample.png";
    /** 鸭子归一化目标高度 */
    private static final float DUCK_TARGET_HEIGHT = 1.5f;
    /** 角色归一化目标高度（人体尺度） */
    private static final float DANCER_TARGET_HEIGHT = 1.7f;

    /** 主角立方体自旋（弧度/秒） */
    private static final float HERO_SPIN_X = 0.3f;
    private static final float HERO_SPIN_Y = 0.8f;
    /** 模型自转（仅 Y 轴） */
    private static final float MODEL_SPIN_Y = 0.6f;

    /** 点光源轨道参数：{半径, 高度, 角速度(rad/s), 初相, r, g, b} */
    private static final float[][] LIGHT_ORBITS = {
            {4.0f, 0.8f, 0.5f, 0.0f, 1.00f, 0.30f, 0.25f},   // 红
            {5.5f, 1.4f, 0.7f, 2.1f, 0.30f, 1.00f, 0.40f},   // 绿
            {3.0f, 0.4f, 0.9f, 4.2f, 0.35f, 0.55f, 1.00f},   // 蓝
    };
    private static final float LIGHT_INTENSITY = 4.0f;
    /** 衰减三项式（有效范围约 10 单位，LearnOpenGL 经典参数表） */
    private static final float ATT_CONSTANT = 1.0f;
    private static final float ATT_LINEAR = 0.35f;
    private static final float ATT_QUADRATIC = 0.44f;
    /** 灯标记小方块的边长 */
    private static final float MARKER_SIZE = 0.12f;

    private final World world = new World();
    private final Camera camera = new Camera();
    private final ParticleEngine particles = new ParticleEngine();
    /** 黄昏主光：冷色、低强度（环境光在着色器里与其解耦） */
    private final Light light = new Light(
            new Vector3f(-0.4f, -1.0f, -0.5f),
            new Vector3f(0.75f, 0.80f, 1.0f),
            0.55f);

    // 资源所有权：程序化网格由场景创建并释放；磁盘资产（纹理/模型）走 AssetManager 引用计数
    private final List<Mesh> ownedMeshes = new ArrayList<>();
    private final List<String> acquiredKeys = new ArrayList<>();
    private final AssetManager assets;
    private final EventBus eventBus;
    /** 共享的钳制草地纹理（立方体们复用，避免重复加载） */
    private Texture grassClampTexture;

    public DemoScene(AssetManager assets, EventBus eventBus) {
        this.assets = assets;
        this.eventBus = eventBus;
    }

    @Override
    public void init() {
        camera.setPosition(0, 1.6f, 6f);
        // 初始视角略微向下（0.12 弧度 ≈ 7°），开局就能看到地面和模型
        camera.getRotation().x = 0.12f;

        // 注册系统（每帧按注册顺序执行；TransformSystem 在改 Transform 的系统之后，
        // CollisionSystem 在 TransformSystem 之后读最新位置）
        world.addSystem(new SpinSystem());
        world.addSystem(new OrbitSystem());
        world.addSystem(new AnimationSystem());
        world.addSystem(particles);          // ParticleEngine 同时是系统(模拟)与渲染对象
        world.addSystem(new TransformSystem());
        world.addSystem(new CollisionSystem(eventBus));
        particles.init();                    // GL 资源须在上下文就绪后创建

        createGround();
        createHeroCubes();
        createScatterCubes();
        createDuck();
        createDancer();
        createPatrolLights();
        createCollisionDemo();
    }

    // ==================== 实体构建 ====================

    private void createGround() {
        Mesh ground = own(Shapes.createPlane(60f, 30f));
        Texture grassRepeat = acquire(GRASS_TEXTURE + "?repeat", Texture.class);

        int e = world.createEntity("ground");
        TransformComponent t = new TransformComponent();
        t.getPosition().set(0, GROUND_Y, 0);
        world.addComponent(e, t);
        world.addComponent(e, new MeshComponent(ground));
        world.addComponent(e, new TextureComponent(grassRepeat));
        world.addComponent(e, new MaterialComponent(new Material(new Vector3f(1, 1, 1), 8f, 0.05f)));
    }

    private void createHeroCubes() {
        Mesh cube = own(Shapes.createCube());
        grassClampTexture = acquire(GRASS_TEXTURE, Texture.class);
        Texture grassClamp = grassClampTexture;

        // 左：纯色材质（橙色，高光较强）
        int e1 = world.createEntity("hero-cube-orange");
        TransformComponent t1 = new TransformComponent();
        t1.getPosition().set(-1.3f, GROUND_Y + 0.5f, 0);
        world.addComponent(e1, t1);
        world.addComponent(e1, new MeshComponent(cube));
        world.addComponent(e1, new MaterialComponent(new Material(new Vector3f(0.9f, 0.45f, 0.15f), 32f, 0.6f)));
        world.addComponent(e1, new SpinComponent(HERO_SPIN_X, HERO_SPIN_Y, 0));
        world.addComponent(e1, new ColliderComponent(0.5f, 0.5f, 0.5f));

        // 右：纹理材质（草地贴图，高光较弱）
        int e2 = world.createEntity("hero-cube-textured");
        TransformComponent t2 = new TransformComponent();
        t2.getPosition().set(1.3f, GROUND_Y + 0.5f, 0);
        world.addComponent(e2, t2);
        world.addComponent(e2, new MeshComponent(cube));
        world.addComponent(e2, new TextureComponent(grassClamp));
        world.addComponent(e2, new MaterialComponent(new Material(new Vector3f(1, 1, 1), 16f, 0.25f)));
        world.addComponent(e2, new SpinComponent(HERO_SPIN_X, HERO_SPIN_Y, 0));
        world.addComponent(e2, new ColliderComponent(0.5f, 0.5f, 0.5f));

        createMetalShowcases(cube);
    }

    /** IBL 展示位：镀铬（低粗糙度镜面金属）与赤金（中粗糙度金属），环境反射的活广告。 */
    private void createMetalShowcases(Mesh cube) {
        // 镀铬立方体：metallic=1, roughness=0.08 —— 像镜子一样映出天空与太阳
        int chrome = world.createEntity("metal-chrome");
        TransformComponent tc = new TransformComponent();
        tc.getPosition().set(-2.8f, GROUND_Y + 0.5f, 1.2f);
        world.addComponent(chrome, tc);
        world.addComponent(chrome, new MeshComponent(cube));
        world.addComponent(chrome, new PbrMaterialComponent(
                new PbrMaterial(new Vector3f(1.0f, 1.0f, 1.0f), 1.0f, 0.08f)));
        world.addComponent(chrome, new SpinComponent(0, 0.5f, 0));

        // 赤金立方体：金属的 F0 = albedo，反射带金色调
        int gold = world.createEntity("metal-gold");
        TransformComponent tg = new TransformComponent();
        tg.getPosition().set(2.8f, GROUND_Y + 0.5f, 1.2f);
        world.addComponent(gold, tg);
        world.addComponent(gold, new MeshComponent(cube));
        world.addComponent(gold, new PbrMaterialComponent(
                new PbrMaterial(new Vector3f(1.0f, 0.766f, 0.336f), 1.0f, 0.3f)));
        world.addComponent(gold, new SpinComponent(0, -0.5f, 0));
    }

    private void createScatterCubes() {
        Mesh cube = own(Shapes.createCube());
        Texture grassClamp = grassClampTexture; // 复用主角立方体已加载的钳制纹理
        // {x, z, scale, 是否纹理(1/0), r, g, b}
        float[][] scatter = {
                {-4.0f, -3.0f, 1.0f, 1, 1.0f, 1.0f, 1.0f},
                {3.5f, -5.0f, 2.0f, 0, 0.30f, 0.55f, 0.95f},
                {-2.0f, -7.0f, 1.5f, 1, 1.0f, 1.0f, 1.0f},
                {5.0f, 2.0f, 0.8f, 0, 0.95f, 0.30f, 0.35f},
                {-6.0f, 1.0f, 2.4f, 1, 1.0f, 1.0f, 1.0f},
                {1.0f, -10.0f, 3.0f, 0, 0.90f, 0.80f, 0.30f},
                {-8.0f, -8.0f, 1.2f, 0, 0.55f, 0.35f, 0.85f},
                {7.0f, -8.0f, 1.6f, 1, 1.0f, 1.0f, 1.0f},
        };
        for (int i = 0; i < scatter.length; i++) {
            float[] s = scatter[i];
            float scale = s[2];
            int e = world.createEntity("scatter-cube-" + i);
            TransformComponent t = new TransformComponent();
            t.getPosition().set(s[0], GROUND_Y + scale / 2.0f, s[1]);
            t.getScale().set(scale, scale, scale);
            // 不同的初始朝向，避免整齐划一
            t.getRotation().set(0, i * 0.7f, 0);
            world.addComponent(e, t);
            world.addComponent(e, new MeshComponent(cube));
            if (s[3] == 1) {
                world.addComponent(e, new TextureComponent(grassClamp));
            }
            world.addComponent(e, new MaterialComponent(
                    new Material(new Vector3f(s[4], s[5], s[6]), 24f, 0.35f)));
            world.addComponent(e, new ColliderComponent(0.5f, 0.5f, 0.5f));
        }
    }

    private void createDuck() {
        LoadedModel duck = acquire(DUCK_MODEL, LoadedModel.class);

        // 按包围盒自动归一化：模型原始单位不可控（Duck.glb 是厘米级，高约 154）
        float[] aabb = duck.getAabb();
        float s = DUCK_TARGET_HEIGHT / duck.getHeight();
        float posY = GROUND_Y - aabb[1] * s; // 补偿底部偏移使脚底贴地

        // 场景图层级：根实体持有位置/缩放/自旋，网格实体作为子节点（本地变换为单位阵）。
        // 整体旋转由父级驱动，子网格自动跟随——不再需要"每个实体挂相同 Spin 参数"的硬同步
        int root = world.createEntity("duck-root");
        TransformComponent rootT = new TransformComponent();
        rootT.getPosition().set(0, posY, 1.5f);
        rootT.getScale().set(s, s, s);
        world.addComponent(root, rootT);
        world.addComponent(root, new SpinComponent(0, MODEL_SPIN_Y, 0));
        // 鸭子碰撞体挂在根实体：本地半长用模型原始单位（世界半长 = 本地 × 缩放 s）
        world.addComponent(root, new ColliderComponent(70f, 85f, 60f));

        for (int i = 0; i < duck.getMeshCount(); i++) {
            int child = world.createEntity("duck-mesh-" + i);
            world.addComponent(child, new TransformComponent());       // 本地单位变换
            world.addComponent(child, new ParentComponent(root));      // 挂到根节点
            world.addComponent(child, new MeshComponent(duck.getMesh(i)));
            if (duck.getTexture(i) != null) {
                world.addComponent(child, new TextureComponent(duck.getTexture(i)));
            }
            // PBR 材质优先（glTF 原生工作流），经典材质回退
            PbrMaterial pbr = duck.getPbrMaterial(i);
            if (pbr != null) {
                world.addComponent(child, new PbrMaterialComponent(pbr));
            } else {
                Material mat = duck.getMaterial(i);
                world.addComponent(child, new MaterialComponent(mat != null ? mat : Material.defaults()));
            }
        }
    }

    private void createPatrolLights() {
        for (int i = 0; i < LIGHT_ORBITS.length; i++) {
            float[] o = LIGHT_ORBITS[i];
            int e = world.createEntity("patrol-light-" + i);

            TransformComponent t = new TransformComponent();
            world.addComponent(e, t);
            world.addComponent(e, new OrbitComponent(o[0], o[1], o[2], o[3]));

            // 关键技巧：PointLight 直接共享 Transform 的 position 向量实例，
            // OrbitSystem 移动 Transform 时灯光位置零成本同步
            PointLight pl = new PointLight(t.getPosition(), new Vector3f(o[4], o[5], o[6]),
                    LIGHT_INTENSITY, ATT_CONSTANT, ATT_LINEAR, ATT_QUADRATIC);
            world.addComponent(e, new PointLightComponent(pl));

            // 巡游灯喷洒同色火花（EmitterComponent 自动取同实体灯光颜色）
            world.addComponent(e, new EmitterComponent(40f, 1.2f, 0.9f, 1.0f));

            // 自发光标记：纯色顶点色网格，无材质组件 -> 顶点色管线，不受光照影响
            Mesh marker = own(Shapes.createSolidColorCube(o[4], o[5], o[6]));
            world.addComponent(e, new MeshComponent(marker));
            t.getScale().set(MARKER_SIZE, MARKER_SIZE, MARKER_SIZE);
        }
    }

    /**
     * 骨骼动画展示：CesiumMan 行走循环（glTF 蒙皮 + 单动画）。
     * 归一化到 1.7 米高（人体尺度），面向相机。
     */
    private void createDancer() {
        LoadedModel man = acquire(DANCER_MODEL, LoadedModel.class);
        if (!man.hasAnimations()) {
            log.warn("模型 {} 不含动画，跳过角色创建", DANCER_MODEL);
            return;
        }
        float[] aabb = man.getAabb();
        float s = DANCER_TARGET_HEIGHT / man.getHeight();

        int e = world.createEntity("dancer");
        TransformComponent t = new TransformComponent();
        t.getPosition().set(-2.2f, GROUND_Y - aabb[1] * s, 3.0f);
        t.getScale().set(s, s, s);
        t.getRotation().set(0, 0.5f, 0); // 稍微侧身面向初始相机
        world.addComponent(e, t);
        world.addComponent(e, new MeshComponent(man.getMesh(0)));
        if (man.getTexture(0) != null) {
            world.addComponent(e, new TextureComponent(man.getTexture(0)));
        }
        PbrMaterial pbr = man.getPbrMaterial(0);
        world.addComponent(e, new PbrMaterialComponent(
                pbr != null ? pbr : PbrMaterial.defaults()));
        // 动画组件：第 0 段动画，1 倍速，循环播放（骨骼矩阵由 AnimationSystem 写入）
        world.addComponent(e, new AnimationComponent(
                man.getAnimations().get(0), man.getSkeleton(), 1.0f, true));
    }

    /**
     * 碰撞演示装置：一个沿半径 4 轨道运行的发光小球，每圈都会撞上
     * 放在轨道上的固定碰撞块 -> CollisionSystem 发布 CollisionEvent -> 音频/日志响应。
     */
    private void createCollisionDemo() {
        Mesh cube = own(Shapes.createCube());

        // 轨道小球（红色自旋小方块，带碰撞体）
        int ball = world.createEntity("wrecking-ball");
        TransformComponent bt = new TransformComponent();
        bt.getScale().set(0.5f, 0.5f, 0.5f);
        world.addComponent(ball, bt);
        world.addComponent(ball, new OrbitComponent(4.0f, GROUND_Y + 0.5f, 0.8f, 0f));
        world.addComponent(ball, new MeshComponent(cube));
        world.addComponent(ball, new MaterialComponent(new Material(new Vector3f(1f, 0.2f, 0.25f), 48f, 0.8f)));
        world.addComponent(ball, new SpinComponent(0, 2.0f, 0));
        world.addComponent(ball, new ColliderComponent(0.5f, 0.5f, 0.5f));

        // 固定碰撞块（紫色，正好在轨道上：角度 0 时小球位置 = (4, h, 0)）
        int bumper = world.createEntity("bumper");
        TransformComponent st = new TransformComponent();
        st.getPosition().set(4.0f, GROUND_Y + 0.5f, 0);
        world.addComponent(bumper, st);
        world.addComponent(bumper, new MeshComponent(cube));
        world.addComponent(bumper, new MaterialComponent(new Material(new Vector3f(0.6f, 0.3f, 0.9f), 24f, 0.4f)));
        world.addComponent(bumper, new ColliderComponent(0.5f, 0.5f, 0.5f));
    }

    // ==================== 每帧驱动 / 资源 ====================

    /** 每帧更新：委托给 World（依次执行已注册的系统）。 */
    @Override
    public void update(float deltaTime) {
        world.update(deltaTime);
    }

    private Mesh own(Mesh mesh) {
        ownedMeshes.add(mesh);
        return mesh;
    }

    /** 从资产管理器获取（引用计数 +1），key 记录下来供 cleanup 时释放。 */
    private <T> T acquire(String key, Class<T> type) {
        acquiredKeys.add(key);
        return assets.acquire(key, type);
    }

    public World getWorld() {
        return world;
    }

    /** 粒子引擎：Game 在场景遍内调用其 render()（模拟已由 World 调度）。 */
    public ParticleEngine getParticleEngine() {
        return particles;
    }

    @Override
    public Camera getCamera() {
        return camera;
    }

    @Override
    public Light getLight() {
        return light;
    }

    @Override
    public void cleanup() {
        particles.cleanup();
        ownedMeshes.forEach(Mesh::cleanup);
        ownedMeshes.clear();
        acquiredKeys.forEach(assets::release);
        acquiredKeys.clear();
    }
}
