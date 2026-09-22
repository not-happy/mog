package com.mog.core;

import com.mog.asset.AssetManager;
import com.mog.audio.AudioEngine;
import com.mog.core.event.CollisionEvent;
import com.mog.core.event.EventBus;
import com.mog.core.event.PostToggleEvent;
import com.mog.core.event.WindowResizedEvent;
import com.mog.ecs.components.WorldAabbComponent;
import com.mog.game.CameraController;
import com.mog.game.DemoScene;
import com.mog.game.SaveManager;
import com.mog.input.InputHandler;
import com.mog.physics.Ray;
import com.mog.render.Environment;
import com.mog.render.LoadedModel;
import com.mog.render.ModelLoader;
import com.mog.render.PostProcessor;
import com.mog.render.Renderer;
import com.mog.render.Shader;
import com.mog.render.ShaderSet;
import com.mog.render.Texture;
import com.mog.ui.FontAtlas;
import com.mog.ui.SpriteBatch;
import com.mog.ui.TextRenderer;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * 游戏主类：装配各子系统并驱动主循环。
 *
 * 循环结构（固定时间步长 + 可变渲染）：
 *   pollEvents -> input -> 固定步长模拟(60Hz, accumulator) -> 相机(帧步长)
 *   -> render(阴影遍/场景遍/后处理) -> swapBuffers
 *
 * 固定步长的意义：世界模拟（未来的物理/帧判定）与渲染帧率解耦，
 * 无论 30fps 还是 144fps，模拟行为完全一致、可重现。
 */
public class Game {

    private static final Logger log = LoggerFactory.getLogger(Game.class);
    private static final String WINDOW_TITLE = "mog-engine";

    /** 固定模拟步长：60Hz（物理/玩法逻辑的标准节拍） */
    private static final float FIXED_DT = 1f / 60f;
    /** 单帧最多补算的模拟步数：防"死亡螺旋"（慢帧->更多补算->更慢） */
    private static final int MAX_FIXED_STEPS = 5;

    private final EventBus eventBus = new EventBus();
    private final AssetManager assets = new AssetManager();
    private final AudioEngine audio = new AudioEngine();
    private final SaveManager saveManager = new SaveManager();

    private Window window;
    private Timer timer;
    private InputHandler input;
    private Renderer renderer;
    private PostProcessor post;
    private Environment environment;
    private ShaderSet shaders;
    private Scene scene;
    private CameraController cameraController;
    /** 文本 HUD（系统字体不可用时为 null，自动降级） */
    private FontAtlas font;
    private TextRenderer text;
    /** 2D 精灵批渲染（HUD 图标演示） */
    private SpriteBatch spriteBatch;
    private Texture hudSprite;
    private static final String HUD_SPRITE_KEY = "/textures/sample.png";
    private int currentFps;
    /** F3 切换：在控制台打印每帧原始鼠标增量与相机角度，用于诊断输入漂移/方向问题 */
    private boolean inputDebug;
    private int debugCounter;
    private boolean firstFrameDone;
    /** 准星拾取状态 */
    private final Ray pickRay = new Ray();
    private final Vector3f pickDir = new Vector3f();
    private String pickedName;

    /** HUD 用到的 CJK 字符（烘焙进字体图集；ASCII 自动包含） */
    private static final String HUD_CJK_CHARS = "帧率固定步长实体资产缓存后处理开关鼠标捕获输入调试退出相机坐标音乐存档读选中左键拾取无";

    public void start() {
        log.info("===== mog-engine 启动 =====");
        try {
            init();
            loop();
        } catch (Exception e) {
            log.error("游戏运行异常", e);
        } finally {
            cleanup();
            log.info("===== mog-engine 退出 =====");
        }
    }

    private void init() {
        registerAssetLoaders();

        window = new Window(WINDOW_TITLE, 1280, 720, true);
        window.init();

        timer = new Timer();
        input = new InputHandler(window);

        renderer = new Renderer();
        renderer.init();
        renderer.setViewport(window.getFbWidth(), window.getFbHeight());

        post = new PostProcessor();
        post.init(window.getFbWidth(), window.getFbHeight());

        shaders = new ShaderSet(
                Shader.loadFromClasspath("/shaders/default.vert", "/shaders/default.frag"),
                Shader.loadFromClasspath("/shaders/textured.vert", "/shaders/textured.frag"),
                Shader.loadFromClasspath("/shaders/lit.vert", "/shaders/lit.frag"),
                Shader.loadFromClasspath("/shaders/pbr.vert", "/shaders/pbr.frag"),
                Shader.loadFromClasspath("/shaders/skinned_pbr.vert", "/shaders/pbr.frag"),
                Shader.loadFromClasspath("/shaders/shadow_depth.vert", "/shaders/shadow_depth.frag"));

        scene = new DemoScene(assets, eventBus);
        scene.init();

        // IBL 环境光照：程序化天空 -> 辐照度/预滤波/BRDF LUT
        environment = new Environment();
        environment.build(scene.getLight());
        renderer.setEnvironment(environment);

        cameraController = new CameraController(scene.getCamera());
        window.setMouseCaptured(true); // 默认锁定光标，F1 切换

        // 文本 HUD：探测系统字体烘焙图集（失败则降级为无文本，不影响运行）
        font = FontAtlas.loadSystemFont(24, HUD_CJK_CHARS);
        if (font != null) {
            text = new TextRenderer(font);
        }
        spriteBatch = new SpriteBatch();
        hudSprite = assets.acquire(HUD_SPRITE_KEY, Texture.class);

        audio.init(); // 无音频设备时自动降级为哑模式

        wireEvents();
    }

    /** 组合根：注册各类资产的加载/释放策略。 */
    private void registerAssetLoaders() {
        // 纹理：key 可带 ?repeat 后缀（同图不同环绕模式 = 不同资产）
        assets.registerLoader(Texture.class, new AssetManager.Loader<>() {
            @Override
            public Texture load(String key) {
                boolean repeat = key.endsWith("?repeat");
                String path = repeat ? key.substring(0, key.length() - "?repeat".length()) : key;
                return new Texture(path, repeat);
            }

            @Override
            public void dispose(Texture asset) {
                asset.cleanup();
            }
        });
        // 模型（Assimp 解析 + GL 上传 + 材质/贴图）
        assets.registerLoader(LoadedModel.class, new AssetManager.Loader<>() {
            @Override
            public LoadedModel load(String key) {
                return ModelLoader.load(key);
            }

            @Override
            public void dispose(LoadedModel asset) {
                asset.cleanup();
            }
        });
    }

    /** 事件订阅：子系统之间通过事件解耦（互不持有引用）。 */
    private void wireEvents() {
        eventBus.subscribe(WindowResizedEvent.class, e -> {
            renderer.setViewport(e.width(), e.height());
            post.resize(e.width(), e.height());
        });
        // 演示事件驱动：音频/UI 等都可以订阅事件而不认识 Game（F5 切换后处理时"哔"一声）
        eventBus.subscribe(PostToggleEvent.class, e -> {
            log.info("后处理已{}", e.enabled() ? "开启" : "关闭");
            audio.playBlip();
        });
        // 碰撞事件 -> 提示音 + 日志（轨道球每 ~8 秒撞一次碰撞块，不会刷屏）
        eventBus.subscribe(CollisionEvent.class, e -> {
            log.info("碰撞: {} <-> {}", e.nameA(), e.nameB());
            audio.playBlip();
        });
    }

    private void loop() {
        float frameTime;
        float simAccumulator = 0f;   // 固定步长模拟的时间欠账
        float fpsAccumulator = 0f;   // FPS 统计窗口
        int frames = 0;

        while (!window.isCloseRequested() && !input.isEscapePressed()) {
            // dt 钳制：断点/GC/切窗口回来后的巨大首帧耗时不会让模拟爆炸
            frameTime = Math.min(timer.getElapsedTime(), 0.25f);
            frames++;
            fpsAccumulator += frameTime;

            // 每 0.5 秒刷新一次标题栏 FPS
            if (fpsAccumulator >= 0.5f) {
                currentFps = Math.round(frames / fpsAccumulator);
                window.updateTitle(currentFps);
                fpsAccumulator = 0;
                frames = 0;
            }

            window.pollEvents();
            input.update();
            handleHotkeys();
            handlePicking();

            // ===== 固定步长模拟（世界逻辑，60Hz 节拍）=====
            simAccumulator += frameTime;
            int steps = 0;
            while (simAccumulator >= FIXED_DT && steps < MAX_FIXED_STEPS) {
                scene.update(FIXED_DT);
                simAccumulator -= FIXED_DT;
                steps++;
            }
            if (steps >= MAX_FIXED_STEPS) {
                simAccumulator = 0; // 追不上就丢弃欠账（渲染帧率低于模拟帧率过多时）
            }

            // ===== 相机走帧步长（输入手感优先，与模拟解耦）=====
            cameraController.update(input, frameTime, window.isMouseCaptured());

            // 输入诊断：每 10 帧记录一次原始增量与相机角度
            if (inputDebug && ++debugCounter % 10 == 0) {
                var rot = scene.getCamera().getRotation();
                log.info(String.format("[input] dx=%8.1f dy=%8.1f  yaw=%+.4f pitch=%+.4f",
                        input.getDeltaX(), input.getDeltaY(), rot.y, rot.x));
            }

            // ===== 渲染（每帧一次）：阴影深度遍 -> 场景遍 -> 粒子 -> 后处理链 =====
            if (window.takeResized()) {
                eventBus.publish(new WindowResizedEvent(window.getFbWidth(), window.getFbHeight()));
            }
            renderer.renderShadowDepthPass(scene.getWorld(), shaders.getShadowDepth(), scene.getLight());
            post.beginScene();
            renderer.prepare();
            renderer.render(scene.getWorld(), shaders, scene.getCamera(), scene.getLight());
            // 粒子在场景 FBO 内绘制（参与泛光），主遍之后、后处理之前
            if (scene.getParticleEngine() != null) {
                scene.getParticleEngine().render(scene.getCamera(),
                        renderer.getProjectionMatrix(), window.getFbHeight());
            }
            post.process();

            // HUD：后处理之后直绘屏幕（不参与泛光/暗角）
            drawHud();

            window.swapBuffers();

            if (!firstFrameDone) {
                firstFrameDone = true;
                log.info("首帧渲染完成（全管线贯通：阴影/场景/粒子/后处理/HUD）");
            }
        }
    }

    /** 鼠标左键：从屏幕中心（准星）发射线，拾取最近的碰撞体。 */
    private void handlePicking() {
        if (!input.isMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            return;
        }
        var camera = scene.getCamera();
        camera.getForward(pickDir);
        pickRay.set(camera.getPosition(), pickDir);

        float nearest = Float.MAX_VALUE;
        pickedName = null;
        for (int e : scene.getWorld().view(WorldAabbComponent.class)) {
            float t = pickRay.intersectAabb(
                    scene.getWorld().getComponent(e, WorldAabbComponent.class).getAabb());
            if (t >= 0 && t < nearest) {
                nearest = t;
                pickedName = scene.getWorld().getName(e);
            }
        }
        if (pickedName != null) {
            log.info(String.format("拾取: %s (距离 %.2f)", pickedName, nearest));
            audio.playBlip();
        } else {
            log.info("拾取: 未命中");
        }
    }

    /** 调试 HUD：帧率/实体数/资产缓存/相机坐标/快捷键提示。 */
    private void drawHud() {
        if (text == null) {
            return;
        }
        var cam = scene.getCamera().getPosition();
        float line = text.getFont().getLineHeight() + 6f;
        float y = line;

        text.begin(window.getFbWidth(), window.getFbHeight());
        text.drawText(16, y, String.format("mog-engine   帧率: %d FPS   固定步长: 60Hz", currentFps),
                1f, 1f, 1f, 1f, 0.92f);
        y += line;
        text.drawText(16, y, String.format("实体: %d   资产缓存: %d   后处理: %s",
                        scene.getWorld().getEntityCount(), assets.getCacheSize(),
                        post.isEnabled() ? "开" : "关"),
                1f, 0.85f, 0.92f, 0.8f, 0.92f);
        y += line;
        text.drawText(16, y, String.format("相机坐标: (%.1f, %.1f, %.1f)", cam.x, cam.y, cam.z),
                1f, 0.85f, 0.92f, 0.8f, 0.92f);
        y += line;
        text.drawText(16, y, "F1 鼠标捕获   F3 输入调试   F5 后处理   F6 音乐   F9 存档   F10 读档   ESC 退出",
                1f, 1f, 1f, 1f, 0.55f);
        y += line;
        text.drawText(16, y, "左键: 准星拾取   选中: " + (pickedName != null ? pickedName : "无"),
                1f, 1f, 0.95f, 0.5f, 0.92f);

        // 屏幕中心准星
        text.drawText(window.getFbWidth() / 2f - 5, window.getFbHeight() / 2f + 8, "+",
                1f, 1f, 1f, 1f, 0.7f);
        text.end();

        // 2D 精灵演示（SpriteBatch 管线）：右下角"小地图"占位 + 色调变体
        int w = window.getFbWidth();
        int h = window.getFbHeight();
        spriteBatch.begin(w, h);
        spriteBatch.draw(hudSprite, w - 116, h - 116, 100, 100, 1f, 1f, 1f, 0.9f);
        spriteBatch.draw(hudSprite, w - 190, h - 84, 64, 64, 1.0f, 0.65f, 0.55f, 0.9f);
        spriteBatch.end();
    }

    private void handleHotkeys() {
        if (input.isKeyJustPressed(GLFW_KEY_F1)) {
            window.setMouseCaptured(!window.isMouseCaptured());
            // 光标被重新定位，重置基准点，避免位置跳变传入相机
            input.resetMouse();
        }
        if (input.isKeyJustPressed(GLFW_KEY_F3)) {
            inputDebug = !inputDebug;
            log.info("[input] 调试输出 {}", inputDebug ? "开启" : "关闭");
        }
        if (input.isKeyJustPressed(GLFW_KEY_F5)) {
            post.toggleEnabled();
            eventBus.publish(new PostToggleEvent(post.isEnabled()));
        }
        if (input.isKeyJustPressed(GLFW_KEY_F6)) {
            audio.toggleMusic();
        }
        if (input.isKeyJustPressed(GLFW_KEY_F9)) {
            saveManager.save(scene.getWorld(), scene.getCamera());
            audio.playBlip();
        }
        if (input.isKeyJustPressed(GLFW_KEY_F10)) {
            saveManager.load(scene.getWorld(), scene.getCamera());
            audio.playBlip();
        }
    }

    private void cleanup() {
        audio.shutdown();
        if (scene != null) {
            scene.cleanup();
        }
        if (shaders != null) {
            shaders.cleanup();
        }
        if (environment != null) {
            environment.cleanup();
        }
        if (post != null) {
            post.cleanup();
        }
        if (renderer != null) {
            renderer.cleanup();
        }
        // GL 资产（纹理/模型/网格）必须在窗口上下文销毁之前释放
        if (spriteBatch != null) {
            spriteBatch.cleanup();
        }
        assets.release(HUD_SPRITE_KEY);
        if (text != null) {
            text.cleanup();
        }
        if (font != null) {
            font.cleanup();
        }
        assets.disposeAll();
        if (window != null) {
            window.cleanup();
        }
        assets.shutdown();
        eventBus.clear();
    }
}
