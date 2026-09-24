package com.mog.render;

import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * 后处理管线：场景渲进 SceneFBO，再经 泛光链（明亮提取 -> 高斯 ping-pong）
 * 与 合成遍（场景+泛光+暗角+饱和度）输出到屏幕。
 *
 * 使用方式（Game 主循环）：
 *   renderer.renderShadowDepthPass(...);   // 阴影（自己的 FBO）
 *   post.beginScene();                     // 绑定场景 FBO
 *   renderer.prepare(); renderer.render(...);
 *   post.process();                        // 后处理链 -> 屏幕
 *
 * F5 开关：关闭时合成遍以 bloomStrength=0、无暗角、原饱和度直通场景纹理。
 */
public class PostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PostProcessor.class);

    /** 模糊 ping-pong 遍数（每遍一个方向，H/V 交替）；软件渲染器上 6 遍是画质/帧率平衡点 */
    private static final int BLUR_PASSES = 6;
    // ===== 效果参数 =====
    private static final float BLOOM_THRESHOLD = 0.75f;
    private static final float BLOOM_SOFT_KNEE = 0.4f;
    private static final float BLOOM_STRENGTH = 0.7f;
    private static final float VIGNETTE_STRENGTH = 0.3f;
    private static final float SATURATION = 1.12f;
    /** 曝光（HDR 亮度总闸）：整体偏亮调低、偏暗调高，1.0 = 标准 */
    private static final float EXPOSURE = 1.0f;

    // ===== 纪元色调分级（GDD D7）：显示空间末端的整体色调乘法 =====
    // 由 Game/场景经 setTint 驱动；参数是 Game 级全局态，场景切换时集中复位为无色调。
    private static final org.joml.Vector3f IDENTITY_TINT = new org.joml.Vector3f(1f, 1f, 1f);
    private final org.joml.Vector3f tintColor = new org.joml.Vector3f(1f, 1f, 1f);
    private float tintStrength = 0f;

    /** 设置整体色调分级（strength=0 无效果；1 = 完全染色）。 */
    public void setTint(float r, float g, float b, float strength) {
        tintColor.set(r, g, b);
        tintStrength = Math.max(0f, Math.min(1f, strength));
    }

    private FrameBuffer sceneFbo;
    private FrameBuffer brightFbo;
    private final FrameBuffer[] pingPongFbo = new FrameBuffer[2];

    private Shader brightShader;
    private Shader blurShader;
    private Shader compositeShader;
    private Mesh quad;

    private int width;
    private int height;
    private boolean enabled = true;
    private int frameCount;

    public void init(int width, int height) {
        this.width = width;
        this.height = height;

        sceneFbo = new FrameBuffer(width, height, true, true);           // HDR: RGBA16F + 深度
        brightFbo = new FrameBuffer(width / 2, height / 2, false, true); // 泛光链全程 HDR，保留 >1 亮度
        pingPongFbo[0] = new FrameBuffer(width / 2, height / 2, false, true);
        pingPongFbo[1] = new FrameBuffer(width / 2, height / 2, false, true);

        brightShader = Shader.loadFromClasspath("/shaders/post.vert", "/shaders/bright.frag");
        blurShader = Shader.loadFromClasspath("/shaders/post.vert", "/shaders/blur.frag");
        compositeShader = Shader.loadFromClasspath("/shaders/post.vert", "/shaders/composite.frag");

        // 全屏 quad：两个三角形覆盖 NDC [-1,1]
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] indices = {0, 1, 2, 2, 3, 0};
        quad = Mesh.textured(positions, uvs, indices);

        log.info("后处理管线就绪: 场景 {}x{}, 泛光链 {}x{} ({} 遍模糊)",
                width, height, width / 2, height / 2, BLUR_PASSES);
    }

    /** 场景遍开始：绑定场景 FBO（Renderer 的视口设置与其尺寸一致）。 */
    public void beginScene() {
        sceneFbo.bind();
    }

    /** 场景遍结束后执行完整后处理链，结果输出到默认帧缓冲（屏幕）。 */
    public void process() {
        process(null);
    }

    /** @param traceLogger 非 null 时输出分段耗时日志（诊断用） */
    public void process(org.slf4j.Logger traceLogger) {
        if (traceLogger != null) {
            traceLogger.info("[post] 开始");
        }
        // 全屏合成是纯 2D 操作，必须关闭深度测试：
        // 默认帧缓冲的深度缓冲保留着上一帧 quad 的深度(0.5)，开着 LESS 比较会
        // 把本帧 quad 整屏拒掉（全黑事故）。写完恢复，不影响场景遍。
        glDisable(GL_DEPTH_TEST);
        // ===== 泛光链（仅启用时）=====
        int bloomTexture = 0;
        if (enabled) {
            // 1. 明亮提取：场景 -> brightFbo
            brightFbo.bind();
            brightShader.bind();
            bindTexture(GL_TEXTURE0, sceneFbo.getColorTexture());
            brightShader.setUniform("sceneTexture", 0);
            brightShader.setUniform("threshold", BLOOM_THRESHOLD);
            brightShader.setUniform("softKnee", BLOOM_SOFT_KNEE);
            quad.render();
            brightShader.unbind();
            if (traceLogger != null) {
                traceLogger.info("[post] 明亮提取完成");
            }

            // 2. 高斯模糊 ping-pong：brightFbo -> pp0 -> pp1 -> pp0 ...（H/V 交替）
            blurShader.bind();
            blurShader.setUniform("image", 0);
            blurShader.setUniform("texelSize", 1.0f / brightFbo.getWidth(), 1.0f / brightFbo.getHeight());
            int src = brightFbo.getColorTexture();
            for (int i = 0; i < BLUR_PASSES; i++) {
                FrameBuffer dst = pingPongFbo[i % 2];
                dst.bind();
                bindTexture(GL_TEXTURE0, src);
                blurShader.setUniform("horizontal", i % 2 == 0 ? 1 : 0);
                quad.render();
                src = dst.getColorTexture();
            }
            blurShader.unbind();
            bloomTexture = src;
            if (traceLogger != null) {
                traceLogger.info("[post] 模糊链完成");
            }
        }

        // ===== 合成遍：场景 + 泛光 + 暗角 + 饱和度 -> 屏幕 =====
        FrameBuffer.unbind();
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT);
        compositeShader.bind();
        bindTexture(GL_TEXTURE0, sceneFbo.getColorTexture());
        compositeShader.setUniform("sceneTexture", 0);
        if (enabled) {
            bindTexture(GL_TEXTURE1, bloomTexture);
            compositeShader.setUniform("bloomTexture", 1);
            compositeShader.setUniform("bloomStrength", BLOOM_STRENGTH);
            compositeShader.setUniform("vignetteStrength", VIGNETTE_STRENGTH);
            compositeShader.setUniform("saturation", SATURATION);
            compositeShader.setUniform("exposure", EXPOSURE);
            compositeShader.setUniform("tintColor", tintColor);
            compositeShader.setUniform("tintStrength", tintStrength);
        } else {
            // 直通模式：全黑泛光 + 无暗角 + 原饱和度 + 标准曝光 + 无色调
            bindTexture(GL_TEXTURE1, pingPongFbo[0].getColorTexture());
            compositeShader.setUniform("bloomTexture", 1);
            compositeShader.setUniform("bloomStrength", 0.0f);
            compositeShader.setUniform("vignetteStrength", 0.0f);
            compositeShader.setUniform("saturation", 1.0f);
            compositeShader.setUniform("exposure", 1.0f);
            compositeShader.setUniform("tintColor", IDENTITY_TINT);
            compositeShader.setUniform("tintStrength", 0.0f);
        }
        quad.render();
        compositeShader.unbind();
        glEnable(GL_DEPTH_TEST);
        if (traceLogger != null) {
            traceLogger.info("[post] 合成完成");
        }

        // 像素自检暂停用：glReadPixels 在 WARP/Mesa-d3d12 上会挂起（驱动级问题），
        // 本地真实 GPU 无此现象。需要时把下方注释放开。
        frameCount++;
        // if (frameCount == 1 || frameCount == 30) {
        //     selfTest(frameCount);
        // }
    }

    /** 读回屏幕中心像素写入日志——黑屏类问题第一时间有数据可查。 */
    private void selfTest(int frame) {
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer px = stack.malloc(4);
            glReadPixels(width / 2, height / 2, 1, 1, GL_RGB, GL_UNSIGNED_BYTE, px);
            log.info("后处理自检(第{}帧): 屏幕中心像素 RGB=({}, {}, {})",
                    frame, px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF);
        }
    }

    public void resize(int width, int height) {
        if (width <= 0 || height <= 0 || (width == this.width && height == this.height)) {
            return;
        }
        this.width = width;
        this.height = height;
        sceneFbo.resize(width, height);
        brightFbo.resize(width / 2, height / 2);
        pingPongFbo[0].resize(width / 2, height / 2);
        pingPongFbo[1].resize(width / 2, height / 2);
    }

    public void toggleEnabled() {
        enabled = !enabled;
        log.info("后处理 {}", enabled ? "开启" : "关闭");
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static void bindTexture(int unit, int textureId) {
        glActiveTexture(unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    public void cleanup() {
        if (quad != null) {
            quad.cleanup();
        }
        if (brightShader != null) {
            brightShader.cleanup();
        }
        if (blurShader != null) {
            blurShader.cleanup();
        }
        if (compositeShader != null) {
            compositeShader.cleanup();
        }
        if (sceneFbo != null) {
            sceneFbo.cleanup();
        }
        if (brightFbo != null) {
            brightFbo.cleanup();
        }
        for (FrameBuffer fbo : pingPongFbo) {
            if (fbo != null) {
                fbo.cleanup();
            }
        }
    }
}
