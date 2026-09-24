package com.mog.core;

import com.mog.asset.AssetManager;
import com.mog.audio.AudioEngine;
import com.mog.core.event.CollisionEvent;
import com.mog.core.event.EpochChangedEvent;
import com.mog.core.event.EventBus;
import com.mog.core.event.PostToggleEvent;
import com.mog.core.event.WindowResizedEvent;
import com.mog.ecs.components.WorldAabbComponent;
import com.mog.game.CameraRig;
import com.mog.game.CosmosScene;
import com.mog.game.CosmosSession;
import com.mog.game.DemoScene;
import com.mog.game.Light;
import com.mog.game.SaveManager;
import com.mog.game.SurfaceScene;
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

import java.util.Arrays;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * 游戏主类：装配各子系统并驱动主循环。
 *
 * 循环结构（固定时间步长 + 可变渲染）：
 *   pollEvents -> input -> 场景热键 -> 固定步长模拟(60Hz) -> 相机装备(帧步长)
 *   -> render(阴影遍/场景遍/场景overlay/粒子) -> 后处理 -> HUD -> swapBuffers
 *
 * 场景：Tab 在地表与宇宙（CosmosScene）间切换；场景差异全部走 Scene 能力方法，
 * 这里不允许 instanceof 具体场景类。
 * 启动参数：--cosmos 直接进宇宙 / --demo 进技术演示场 / --speed=N 宇宙会话初始倍速。
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
    /** 宇宙会话（三体模拟唯一真源）：Game 级持久，固定步长循环里 tick——
     *  地表期间模拟照常推进（GDD D5 一致性铁律），场景切换不重开局 */
    private final CosmosSession cosmos = new CosmosSession(eventBus);

    private Window window;
    private Timer timer;
    private InputHandler input;
    private Renderer renderer;
    private PostProcessor post;
    private Environment environment;
    private ShaderSet shaders;
    private Scene scene;
    /** 场景槽位：Tab 只在 SURFACE <-> COSMOS 间切换；DEMO 仅经 --demo 进入、Tab 离开后不再回来 */
    private enum SceneSlot { SURFACE, COSMOS, DEMO }
    private SceneSlot currentSlot;
    private SceneSlot startSlot = SceneSlot.SURFACE;
    /** 调试加速：--speed=N 写入宇宙会话的初始倍速（0 = 用会话默认 0.5） */
    private double cosmosSpeed;
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
    /** 准星拾取状态（仅地表场景） */
    private final Ray pickRay = new Ray();
    private final Vector3f pickDir = new Vector3f();
    private String pickedName;

    /** HUD 用到的 CJK 字符（烘焙进字体图集；ASCII 自动包含） */
    /** HUD 文案用到的全部 CJK 字符。字体图集为静态烘焙：改 HUD 文案必须同步补字符，
     *  否则 TextRenderer 以空白占位（缺字会 WARN 一次提醒）。 */
    private static final String HUD_CJK_CHARS =
            "帧率固定步长实体资产缓存后处理开关鼠标捕获输入调试退出相机坐标音乐存档读选中左键拾取无"
            + "宇宙场景纪元温度指数模拟时间倍速种子暂停旋滚缩放返回地表拖拽初始化烈寒掠序乱凌空视角文明历"
            // S2 玩法层文案：纪元名(曜期/三家深空)/终局字幕(【】乐章终结·坠焚毁灭算冰封远航恒弹射)
            // /历法(年第日)/宿主星与易天/操作提示(空格转轮)/地表准星与切换/初始化省略号
            + "曜期三家失深已【】乐章终结坠焚·毁灭算冰封远航恒弹射宿主易天次年第日格转轮准星切换…"
            // C2 地表场景操作提示：WASD 平移 / 中键拖拽 / 右键旋转（键已在"左键拾取"烘焙）
            + "移平右";

    public void start(String[] args) {
        List<String> argList = args != null ? Arrays.asList(args) : List.of();
        startSlot = argList.contains("--cosmos") ? SceneSlot.COSMOS
                : argList.contains("--demo") ? SceneSlot.DEMO
                : SceneSlot.SURFACE;
        cosmosSpeed = parseSpeedArg(args);
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

    /** 解析调试加速参数：--speed=N 或 --speed N（返回 0 表示未指定，用场景默认倍速）。 */
    private static double parseSpeedArg(String[] args) {
        if (args == null) {
            return 0;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--speed=")) {
                return parseSpeedValue(a.substring("--speed=".length()), a);
            }
            if (a.equals("--speed") && i + 1 < args.length) {
                return parseSpeedValue(args[i + 1], a);
            }
        }
        return 0;
    }

    private static double parseSpeedValue(String raw, String origin) {
        try {
            double v = Double.parseDouble(raw);
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // fallthrough
        }
        LoggerFactory.getLogger(Game.class).warn("忽略非法的 {} 参数值: {}", origin, raw);
        return 0;
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

        if (cosmosSpeed > 0) {
            cosmos.setSpeed(cosmosSpeed);
            log.info("调试加速: 宇宙会话初始倍速 x{} (--speed)", cosmos.getSpeed());
        }
        currentSlot = startSlot;
        scene = createScene(currentSlot);
        scene.init();
        applySlotRenderState(currentSlot);

        // IBL 环境光照：程序化天空 -> 辐照度/预滤波/BRDF LUT。
        // 用中性默认光与启动场景解耦（lit 管线本就不采样 IBL；地表/宇宙的方向光每帧可变）。
        // C3 债：建造上 PBR 材质后，纪元色需要进 IBL——届时做异步双 Environment 轮换或预烘焙套。
        environment = new Environment();
        environment.build(Light.defaults());
        renderer.setEnvironment(environment);

        // 文本 HUD：探测系统字体烘焙图集（失败则降级为无文本，不影响运行）
        font = FontAtlas.loadSystemFont(24, HUD_CJK_CHARS);
        if (font != null) {
            text = new TextRenderer(font);
        }
        spriteBatch = new SpriteBatch();
        hudSprite = assets.acquire(HUD_SPRITE_KEY, Texture.class);

        audio.init(); // 无音频设备时自动降级为哑模式

        window.setMouseCaptured(scene.wantsMouseCapture());

        wireEvents();
    }

    /** 场景工厂：槽位 -> 场景实例。 */
    private Scene createScene(SceneSlot slot) {
        return switch (slot) {
            case SURFACE -> new SurfaceScene(eventBus, cosmos, post);
            case COSMOS -> new CosmosScene(cosmos);
            case DEMO -> new DemoScene(assets, eventBus);
        };
    }

    /** 槽位级渲染全局态（tint / 阴影正交参数都是 Game 级状态，场景不各自为政）：
     *  切换点集中复位；地表场景的纪元染色由其首次 update 依据当前纪元重新写入。 */
    private void applySlotRenderState(SceneSlot slot) {
        post.setTint(1f, 1f, 1f, 0f);
        if (slot == SceneSlot.SURFACE) {
            // 地表：64m 建造网格 + 仰角 ≥25° 的长光程（extent 48 覆盖半对角线 ~45）
            renderer.setShadowParams(90f, 48f, 1f, 220f);
        } else {
            // 宇宙/演示场默认（场景主体 ±10）
            renderer.setShadowParams(25f, 18f, 1f, 60f);
        }
    }

    /** 组合根：注册各类资产的加载/释放策略。 */
    private void registerAssetLoaders() {
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
        eventBus.subscribe(PostToggleEvent.class, e -> {
            log.info("后处理已{}", e.enabled() ? "开启" : "关闭");
            audio.playBlip();
        });
        // 碰撞事件 -> 提示音 + 日志（轨道球每 ~8 秒撞一次碰撞块，不会刷屏）
        eventBus.subscribe(CollisionEvent.class, e -> {
            log.info("碰撞: {} <-> {}", e.nameA(), e.nameB());
            audio.playBlip();
        });
        // 纪元变更 -> 提示音（日志由 CosmosSession 记录）
        eventBus.subscribe(EpochChangedEvent.class, e -> audio.playBlip());
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

            if (fpsAccumulator >= 0.5f) {
                currentFps = Math.round(frames / fpsAccumulator);
                window.updateTitle(currentFps);
                fpsAccumulator = 0;
                frames = 0;
            }

            window.pollEvents();
            input.update();
            handleHotkeys();
            scene.handleInput(input);
            if (scene.usesCrosshairPicking()) {
                handlePicking();
            }

            // ===== 固定步长模拟（世界逻辑，60Hz 节拍）=====
            // 宇宙会话先于场景 tick：无论玩家在地表还是宇宙，三体模拟按同一节拍推进
            simAccumulator += frameTime;
            int steps = 0;
            while (simAccumulator >= FIXED_DT && steps < MAX_FIXED_STEPS) {
                cosmos.tick(FIXED_DT);
                scene.update(FIXED_DT);
                simAccumulator -= FIXED_DT;
                steps++;
            }
            if (steps >= MAX_FIXED_STEPS) {
                simAccumulator = 0; // 追不上就丢弃欠账
            }

            // ===== 相机装备走帧步长（输入手感优先，与模拟解耦）=====
            CameraRig rig = scene.getCameraRig();
            if (rig != null) {
                rig.update(input, frameTime, window.isMouseCaptured());
            }

            if (inputDebug && ++debugCounter % 10 == 0) {
                var rot = scene.getCamera().getRotation();
                log.info(String.format("[input] dx=%8.1f dy=%8.1f  yaw=%+.4f pitch=%+.4f",
                        input.getDeltaX(), input.getDeltaY(), rot.y, rot.x));
            }

            // ===== 渲染：阴影深度遍 -> 场景遍 -> overlay -> 粒子 -> 后处理 -> HUD =====
            if (window.takeResized()) {
                eventBus.publish(new WindowResizedEvent(window.getFbWidth(), window.getFbHeight()));
            }
            renderer.renderShadowDepthPass(scene.getWorld(), shaders.getShadowDepth(), scene.getLight());
            post.beginScene();
            renderer.prepare();
            renderer.render(scene.getWorld(), shaders, scene.getCamera(), scene.getLight());
            scene.renderOverlay(scene.getCamera(), renderer.getProjectionMatrix());
            if (scene.getParticleEngine() != null) {
                scene.getParticleEngine().render(scene.getCamera(),
                        renderer.getProjectionMatrix(), window.getFbHeight());
            }
            post.process();

            drawHud();

            window.swapBuffers();

            if (!firstFrameDone) {
                firstFrameDone = true;
                log.info("首帧渲染完成（全管线贯通：阴影/场景/overlay/粒子/后处理/HUD）");
            }
        }
    }

    private void handleHotkeys() {
        if (input.isKeyJustPressed(GLFW_KEY_F1)) {
            window.setMouseCaptured(!window.isMouseCaptured());
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
        if (input.isKeyJustPressed(GLFW_KEY_F9) && scene.supportsSaveLoad()) {
            saveManager.save(scene.getWorld(), scene.getCamera());
            audio.playBlip();
        }
        if (input.isKeyJustPressed(GLFW_KEY_F10) && scene.supportsSaveLoad()) {
            saveManager.load(scene.getWorld(), scene.getCamera());
            audio.playBlip();
        }
        if (input.isKeyJustPressed(GLFW_KEY_TAB)) {
            switchScene();
        }
    }

    /** 场景切换：Tab 只在 地表 <-> 宇宙 间往返（DEMO 离开后经 Tab 落到地表槽）。
     *  旧场景释放资源，新场景重建；宇宙会话跨切换持久。 */
    private void switchScene() {
        SceneSlot next = currentSlot == SceneSlot.COSMOS ? SceneSlot.SURFACE : SceneSlot.COSMOS;
        log.info("切换场景 -> {}", next == SceneSlot.COSMOS ? "宇宙视角" : "地表视角");
        scene.cleanup();
        currentSlot = next;
        scene = createScene(next);
        scene.init();
        // 渲染全局态复位集中在切换点（色调分级/阴影正交参数按槽位重置）
        applySlotRenderState(next);
        window.setMouseCaptured(scene.wantsMouseCapture());
        input.resetMouse();
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

    /** HUD：通用行（FPS/快捷键/拾取）+ 场景专属行 + 中心准星。 */
    private void drawHud() {
        if (text == null) {
            return;
        }
        float line = text.getFont().getLineHeight() + 6f;
        float y = line;

        text.begin(window.getFbWidth(), window.getFbHeight());
        text.drawText(16, y, String.format("mog-engine   帧率: %d FPS   固定步长: 60Hz", currentFps),
                1f, 1f, 1f, 1f, 0.92f);
        y += line;
        for (String s : scene.getHudLines()) {
            text.drawText(16, y, s, 1f, 0.85f, 0.92f, 0.8f, 0.92f);
            y += line;
        }
        for (String s : scene.getHintLines()) {
            text.drawText(16, y, s, 1f, 1f, 1f, 1f, 0.55f);
            y += line;
        }
        if (scene.usesCrosshairPicking()) {
            text.drawText(16, y, "选中: " + (pickedName != null ? pickedName : "无"),
                    1f, 1f, 0.95f, 0.5f, 0.92f);
            y += line;
        }
        if (scene.drawsCrosshair()) {
            // 屏幕中心准星
            text.drawText(window.getFbWidth() / 2f - 5, window.getFbHeight() / 2f + 8, "+",
                    1f, 1f, 1f, 1f, 0.7f);
        }
        text.end();

        // 2D 精灵演示（SpriteBatch 管线）：右下角"小地图"占位
        int w = window.getFbWidth();
        int h = window.getFbHeight();
        spriteBatch.begin(w, h);
        spriteBatch.draw(hudSprite, w - 116, h - 116, 100, 100, 1f, 1f, 1f, 0.9f);
        spriteBatch.draw(hudSprite, w - 190, h - 84, 64, 64, 1.0f, 0.65f, 0.55f, 0.9f);
        spriteBatch.end();
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
