package com.mog.render;

import com.mog.game.Light;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.*;

/**
 * IBL 环境光照：运行时程序化生成 PBR 所需的三张查找纹理。
 *
 * 构建流程（一次性，启动时完成）：
 *   1. 程序化黄昏天空 -> 环境立方体贴图 envCubemap (256², RGBA16F)
 *   2. 余弦半球卷积   -> 辐照度图 irradianceMap (32²)          [漫反射 IBL]
 *   3. GGX 重要性采样 -> 预滤波环境图 prefilteredMap (64²×5mip) [镜面 IBL]
 *   4. BRDF 积分      -> brdfLUT (128², RG16F)                 [split-sum 第二项]
 *
 * 分辨率/采样数按软件渲染器可调校（WARP 上构建约数秒）；
 * 真实 GPU 可放大到 LearnOpenGL 标准档位（irradiance 32/prefilter 128/LUT 512）。
 */
public class Environment {

    private static final Logger log = LoggerFactory.getLogger(Environment.class);

    public static final int ENV_SIZE = 256;
    public static final int IRRADIANCE_SIZE = 32;
    public static final int PREFILTER_SIZE = 64;
    public static final int PREFILTER_MIPS = 5;   // 64,32,16,8,4
    public static final float MAX_REFLECTION_LOD = PREFILTER_MIPS - 1; // 与 pbr.frag 一致
    public static final int BRDF_LUT_SIZE = 128;

    private int envCubemap;
    private int irradianceMap;
    private int prefilteredMap;
    private int brdfLUT;
    private int fbo;
    private int rbo;

    private Shader skyShader;
    private Shader irradianceShader;
    private Shader prefilterShader;
    private Shader brdfShader;
    private Mesh cube;
    private Mesh quad;

    /** 六个面的视图矩阵（+X,-X,+Y,-Y,+Z,-Z），标准立方体贴图捕获表 */
    private static Matrix4f[] faceViews() {
        return new Matrix4f[]{
                new Matrix4f().lookAt(0, 0, 0, 1, 0, 0, 0, -1, 0),
                new Matrix4f().lookAt(0, 0, 0, -1, 0, 0, 0, -1, 0),
                new Matrix4f().lookAt(0, 0, 0, 0, 1, 0, 0, 0, 1),
                new Matrix4f().lookAt(0, 0, 0, 0, -1, 0, 0, 0, -1),
                new Matrix4f().lookAt(0, 0, 0, 0, 0, 1, 0, -1, 0),
                new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, -1, 0),
        };
    }

    /** 一次性构建全部 IBL 纹理（GL 上下文就绪后调用）。 */
    public void build(Light sun) {
        long t0 = System.nanoTime();
        // 太阳方向 = 光传播方向取反
        Vector3f sunDir = new Vector3f(sun.getDirection()).normalize().negate();

        skyShader = Shader.loadFromClasspath("/shaders/ibl_capture.vert", "/shaders/ibl_sky.frag");
        irradianceShader = Shader.loadFromClasspath("/shaders/ibl_capture.vert", "/shaders/ibl_irradiance.frag");
        prefilterShader = Shader.loadFromClasspath("/shaders/ibl_capture.vert", "/shaders/ibl_prefilter.frag");
        brdfShader = Shader.loadFromClasspath("/shaders/post.vert", "/shaders/ibl_brdf.frag");
        cube = Shapes.createCube();
        float[] quadPos = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] quadUv = {0, 0, 1, 0, 1, 1, 0, 1};
        quad = Mesh.textured(quadPos, quadUv, new int[]{0, 1, 2, 2, 3, 0});

        fbo = glGenFramebuffers();
        rbo = glGenRenderbuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindRenderbuffer(GL_RENDERBUFFER, rbo);
        // 深度按最大面尺寸分配，小尺寸 pass 复用（附件大于视口是合法的）
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, ENV_SIZE, ENV_SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rbo);

        // ===== 1. 环境立方体贴图：程序化天空 =====
        envCubemap = createCubemap(ENV_SIZE, 1);
        skyShader.bind();
        skyShader.setUniform("sunDir", sunDir);
        captureCubemap(envCubemap, ENV_SIZE, 0, skyShader);

        // ===== 2. 辐照度图：余弦卷积 =====
        irradianceMap = createCubemap(IRRADIANCE_SIZE, 1);
        irradianceShader.bind();
        bindEnvToUnit0(envCubemap);
        irradianceShader.setUniform("environmentMap", 0);
        captureCubemap(irradianceMap, IRRADIANCE_SIZE, 0, irradianceShader);

        // ===== 3. 预滤波环境图：每级 mip 一个粗糙度 =====
        prefilteredMap = createCubemap(PREFILTER_SIZE, PREFILTER_MIPS);
        prefilterShader.bind();
        bindEnvToUnit0(envCubemap);
        prefilterShader.setUniform("environmentMap", 0);
        for (int mip = 0; mip < PREFILTER_MIPS; mip++) {
            float roughness = (float) mip / (PREFILTER_MIPS - 1);
            prefilterShader.setUniform("roughness", roughness);
            captureCubemap(prefilteredMap, PREFILTER_SIZE >> mip, mip, prefilterShader);
        }

        // ===== 4. BRDF 积分 LUT（全屏 quad，无深度需求）=====
        brdfLUT = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, brdfLUT);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, BRDF_LUT_SIZE, BRDF_LUT_SIZE, 0,
                GL_RG, GL_HALF_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glDisable(GL_DEPTH_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, brdfLUT, 0);
        glViewport(0, 0, BRDF_LUT_SIZE, BRDF_LUT_SIZE);
        glClear(GL_COLOR_BUFFER_BIT);
        brdfShader.bind();
        quad.render();
        brdfShader.unbind();
        glEnable(GL_DEPTH_TEST);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("IBL 构建完成: env {}², 辐照度 {}², 预滤波 {}²×{}mips, BRDF LUT {}², 耗时 {} ms",
                ENV_SIZE, IRRADIANCE_SIZE, PREFILTER_SIZE, PREFILTER_MIPS, BRDF_LUT_SIZE, ms);
    }

    /** 创建 RGBA16F 立方体贴图；mips>1 时预分配各级并启用三线性过滤。 */
    private static int createCubemap(int size, int mips) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, id);
        for (int mip = 0; mip < mips; mip++) {
            int mipSize = Math.max(size >> mip, 1);
            for (int face = 0; face < 6; face++) {
                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, mip, GL_RGBA16F,
                        mipSize, mipSize, 0, GL_RGBA, GL_HALF_FLOAT, (java.nio.ByteBuffer) null);
            }
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER,
                mips > 1 ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        return id;
    }

    /** 把着色器输出捕获到立方体贴图的六个面（指定 mip 级）。 */
    private void captureCubemap(int cubemap, int faceSize, int mip, Shader shader) {
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(90.0), 1.0f, 0.1f, 10.0f);
        Matrix4f[] views = faceViews();

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, faceSize, faceSize);
        shader.setUniform("projection", projection);
        for (int face = 0; face < 6; face++) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, cubemap, mip);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            shader.setUniform("view", views[face]);
            cube.render();
        }
    }

    private static void bindEnvToUnit0(int textureId) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_CUBE_MAP, textureId);
    }

    public int getIrradianceMap() {
        return irradianceMap;
    }

    public int getPrefilteredMap() {
        return prefilteredMap;
    }

    public int getBrdfLUT() {
        return brdfLUT;
    }

    public int getEnvCubemap() {
        return envCubemap;
    }

    public void cleanup() {
        if (cube != null) {
            cube.cleanup();
        }
        if (quad != null) {
            quad.cleanup();
        }
        if (skyShader != null) {
            skyShader.cleanup();
        }
        if (irradianceShader != null) {
            irradianceShader.cleanup();
        }
        if (prefilterShader != null) {
            prefilterShader.cleanup();
        }
        if (brdfShader != null) {
            brdfShader.cleanup();
        }
        glDeleteTextures(envCubemap);
        glDeleteTextures(irradianceMap);
        glDeleteTextures(prefilteredMap);
        glDeleteTextures(brdfLUT);
        if (rbo != 0) {
            glDeleteRenderbuffers(rbo);
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
        }
    }
}
