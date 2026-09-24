package com.mog.render;

import com.mog.ecs.Transforms;
import com.mog.ecs.World;
import com.mog.ecs.components.BoneMatricesComponent;
import com.mog.ecs.components.MaterialComponent;
import com.mog.ecs.components.MeshComponent;
import com.mog.ecs.components.PbrMaterialComponent;
import com.mog.ecs.components.PointLightComponent;
import com.mog.ecs.components.TextureComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.ecs.components.WorldMatrixComponent;
import com.mog.game.Light;
import com.mog.game.PointLight;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * 渲染器：负责视口、清屏、矩阵计算与绘制调度，数据来源为 ECS World。
 *
 * 每帧两遍渲染（阴影贴图）：
 *   1. renderShadowDepthPass —— 从光源视角把场景深度渲进 DepthMap（FBO 离屏）
 *   2. render —— 正常着色，lit 管线采样深度图做遮挡比较
 *
 * 管线按组件组合自动选择：
 *   有 MaterialComponent -> 光照管线（方向光阴影 + 点光源 Blinn-Phong）
 *   无材质有 TextureComponent -> 纯纹理管线
 *   都没有 -> 顶点色管线
 */
public class Renderer {

    /** 点光源数量上限（与 lit.frag 的 MAX_POINT_LIGHTS 一致） */
    public static final int MAX_POINT_LIGHTS = 4;
    /** 蒙皮骨骼上限（与 skinned_pbr.vert 的 MAX_BONES 一致） */
    public static final int MAX_BONES = 96;
    /** 预生成 uniform 名前缀，避免每帧字符串拼接 */
    private static final String[] POINT_LIGHT_PREFIX = new String[MAX_POINT_LIGHTS];
    private static final String[] BONE_UNIFORM = new String[MAX_BONES];

    static {
        for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
            POINT_LIGHT_PREFIX[i] = "pointLights[" + i + "].";
        }
        for (int i = 0; i < MAX_BONES; i++) {
            BONE_UNIFORM[i] = "bones[" + i + "]";
        }
    }

    // ===== 阴影正交相机参数：覆盖场景范围的立方体视锥 =====
    // 默认值对应宇宙/演示场（场景主体 ±10）；地表场景经 setShadowParams 放大到 64m 网格范围。
    // 参数是 Game 级全局态——场景切换时由 Game.switchScene 集中复位，场景不各自为政。
    /** 光源位置距场景中心的距离 */
    private float shadowLightDistance = 25f;
    /** 正交视锥半宽 */
    private float shadowExtent = 18f;
    private float shadowNear = 1f;
    private float shadowFar = 60f;

    /** 阴影正交相机参数（默认 25/18/1/60 = 旧硬编码值，调用方不改则视觉无差）。 */
    public void setShadowParams(float lightDistance, float extent, float near, float far) {
        this.shadowLightDistance = lightDistance;
        this.shadowExtent = extent;
        this.shadowNear = near;
        this.shadowFar = far;
    }

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f modelViewMatrix = new Matrix4f();
    // 光空间矩阵（正交投影 × 光源视图）及其临时件
    private final Matrix4f lightSpaceMatrix = new Matrix4f();
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f lightProj = new Matrix4f();
    private final Vector3f lightEye = new Vector3f();
    /** 每帧点光源收集缓存（复用，避免分配） */
    private final List<PointLight> pointLightCache = new ArrayList<>();

    /** 阴影深度图：GL 资源，必须在 init()（GL 上下文就绪后）创建 */
    private DepthMap depthMap;
    /** IBL 环境光照（PBR 管线专用，可为 null 时 PBR 环境项为黑） */
    private Environment environment;

    private float fov = (float) Math.toRadians(60.0f);
    private float zNear = 0.01f;
    private float zFar = 1000.0f;
    /** 主视口尺寸（深度遍会改视口，主遍需要恢复） */
    private int vpWidth = 1;
    private int vpHeight = 1;

    public void init() {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        // HDR 管线：清屏色也要在线性空间（sRGB 0.08,0.09,0.11 ≈ 线性 0.005,0.006,0.009），
        // 否则经合成遍 gamma 编码后背景会明显发灰
        glClearColor(0.005f, 0.006f, 0.009f, 1.0f);
        depthMap = new DepthMap();
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /** 当前投影矩阵（粒子等场景遍内的附加绘制需要）。 */
    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    /** 窗口尺寸变化时更新视口与投影矩阵。 */
    public void setViewport(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        vpWidth = width;
        vpHeight = height;
        glViewport(0, 0, width, height);
        projectionMatrix.setPerspective(fov, (float) width / (float) height, zNear, zFar);
    }

    /** 每帧开始时清屏（主遍）。 */
    public void prepare() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    // ==================== 第一遍：光空间深度 ====================

    /**
     * 阴影深度遍：更新光空间矩阵，把投射阴影的实体（PBR 或经典光照管线实体）深度渲进 DepthMap。
     * 结束后解绑 FBO；主遍开始前由 render() 恢复视口。
     */
    public void renderShadowDepthPass(World world, Shader shadowShader, Light light) {
        updateLightSpaceMatrix(light);

        depthMap.bind();
        shadowShader.bind();
        shadowShader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        for (int e : world.view(MeshComponent.class, TransformComponent.class)) {
            boolean caster = world.hasComponent(e, MaterialComponent.class)
                    || world.hasComponent(e, PbrMaterialComponent.class);
            if (!caster) {
                continue; // 顶点色/纯纹理对象（灯标记等）不投影
            }
            shadowShader.setUniform("model", resolveWorldMatrix(world, e));
            world.getComponent(e, MeshComponent.class).getMesh().render();
        }
        shadowShader.unbind();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** 光空间矩阵 = 正交投影 × 光源视图（光放在传播方向反方向、俯视场景中心）。 */
    private void updateLightSpaceMatrix(Light light) {
        lightEye.set(light.getDirection()).normalize().mul(-shadowLightDistance);
        lightProj.setOrtho(-shadowExtent, shadowExtent, -shadowExtent, shadowExtent,
                shadowNear, shadowFar);
        lightView.setLookAt(lightEye.x, lightEye.y, lightEye.z,
                0, 0, 0,
                0, 1, 0);
        lightSpaceMatrix.set(lightProj).mul(lightView);
    }

    // ==================== 第二遍：主渲染 ====================

    /** 管线类型：0 顶点色 / 1 纯纹理 / 2 经典光照 / 3 PBR / 4 蒙皮 PBR */
    private static final int PIPE_COLOR = 0;
    private static final int PIPE_TEXTURED = 1;
    private static final int PIPE_LIT = 2;
    private static final int PIPE_PBR = 3;
    private static final int PIPE_PBR_SKINNED = 4;

    public void render(World world, ShaderSet shaders, Camera camera, Light light) {
        // 深度遍把视口切到了阴影图尺寸，这里恢复主视口
        glViewport(0, 0, vpWidth, vpHeight);
        camera.getViewMatrix(viewMatrix);
        collectPointLights(world);

        Shader current = null;
        int currentPipe = -1;

        for (int e : world.view(MeshComponent.class, TransformComponent.class)) {
            PbrMaterialComponent pbrComp = world.getComponent(e, PbrMaterialComponent.class);
            MaterialComponent matComp = world.getComponent(e, MaterialComponent.class);
            TextureComponent texComp = world.getComponent(e, TextureComponent.class);
            BoneMatricesComponent bonesComp = world.getComponent(e, BoneMatricesComponent.class);
            PbrMaterial pbr = (pbrComp != null) ? pbrComp.getPbrMaterial() : null;
            Material material = (matComp != null) ? matComp.getMaterial() : null;
            Texture texture = (texComp != null) ? texComp.getTexture() : null;

            // 按组件组合选管线：蒙皮 PBR > PBR > 经典光照 > 纹理 > 顶点色
            int pipe;
            Shader shader;
            if (pbr != null && bonesComp != null) {
                pipe = PIPE_PBR_SKINNED;
                shader = shaders.getPbrSkinned();
            } else if (pbr != null) {
                pipe = PIPE_PBR;
                shader = shaders.getPbr();
            } else if (material != null) {
                pipe = PIPE_LIT;
                shader = shaders.getLit();
            } else if (texture != null) {
                pipe = PIPE_TEXTURED;
                shader = shaders.getTextured();
            } else {
                pipe = PIPE_COLOR;
                shader = shaders.getColor();
            }

            // 仅在着色器切换时重新 bind 并上传共享 uniform（光源/阴影每帧可变，切换即刷新）
            if (shader != current) {
                if (current != null) {
                    current.unbind();
                }
                shader.bind();
                shader.setUniform("projection", projectionMatrix);
                if (pipe == PIPE_LIT || pipe == PIPE_PBR || pipe == PIPE_PBR_SKINNED) {
                    uploadLighting(shader, camera, light);
                }
                if ((pipe == PIPE_PBR || pipe == PIPE_PBR_SKINNED) && environment != null) {
                    uploadIBL(shader);
                }
                current = shader;
                currentPipe = pipe;
            }

            Matrix4f worldMatrix = resolveWorldMatrix(world, e);
            boolean useTexture = texture != null;

            switch (currentPipe) {
                case PIPE_PBR, PIPE_PBR_SKINNED -> {
                    current.setUniform("model", worldMatrix);
                    current.setUniform("albedo", pbr.getAlbedo());
                    current.setUniform("metallic", pbr.getMetallic());
                    current.setUniform("roughness", pbr.getRoughness());
                    current.setUniform("useTexture", useTexture ? 1 : 0);
                    if (currentPipe == PIPE_PBR_SKINNED) {
                        uploadBones(current, bonesComp);
                    }
                }
                case PIPE_LIT -> {
                    current.setUniform("model", worldMatrix);
                    current.setUniform("materialColor", material.getColor());
                    current.setUniform("specularPower", material.getSpecularPower());
                    current.setUniform("specularStrength", material.getSpecularStrength());
                    // bool uniform 用 int 上传（GLSL 规范允许，非 0 为 true）
                    current.setUniform("useTexture", useTexture ? 1 : 0);
                }
                default -> {
                    modelViewMatrix.set(viewMatrix).mul(worldMatrix);
                    current.setUniform("modelView", modelViewMatrix);
                }
            }

            if (useTexture) {
                current.setUniform("texSampler", 0);
                texture.bind();
            }
            world.getComponent(e, MeshComponent.class).getMesh().render();
            if (useTexture) {
                texture.unbind();
            }
        }
        if (current != null) {
            current.unbind();
        }
    }

    /** 上传骨骼矩阵数组（蒙皮管线，每实体每帧——骨骼在动）。 */
    private static void uploadBones(Shader shader, BoneMatricesComponent bones) {
        int count = Math.min(bones.getBoneCount(), MAX_BONES);
        Matrix4f[] mats = bones.getMatrices();
        for (int i = 0; i < count; i++) {
            shader.setUniform(BONE_UNIFORM[i], mats[i]);
        }
    }

    /** 绑定 IBL 三张查找纹理（单元 2/3/4，避开材质的 0 与阴影的 1）。 */
    private void uploadIBL(Shader shader) {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_CUBE_MAP, environment.getIrradianceMap());
        shader.setUniform("irradianceMap", 2);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_CUBE_MAP, environment.getPrefilteredMap());
        shader.setUniform("prefilteredMap", 3);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, environment.getBrdfLUT());
        shader.setUniform("brdfLUT", 4);
    }

    /**
     * 解析实体的世界矩阵：优先用 TransformSystem 缓存的层级结果；
     * 缺失时（如渲染先于首次 update 的极端情况）由本地 TRS 兜底。
     */
    private Matrix4f resolveWorldMatrix(World world, int entity) {
        WorldMatrixComponent wm = world.getComponent(entity, WorldMatrixComponent.class);
        if (wm != null) {
            return wm.getMatrix();
        }
        Transforms.buildLocalMatrix(world.getComponent(entity, TransformComponent.class), modelMatrix);
        return modelMatrix;
    }

    /** 从 World 收集所有点光源组件（复用缓存列表）。 */
    private void collectPointLights(World world) {
        pointLightCache.clear();
        for (int e : world.view(PointLightComponent.class)) {
            pointLightCache.add(world.getComponent(e, PointLightComponent.class).getLight());
        }
    }

    /** 上传方向光、点光源数组与阴影相关的全部 uniform。 */
    private void uploadLighting(Shader shader, Camera camera, Light light) {
        shader.setUniform("view", viewMatrix);
        shader.setUniform("viewPos", camera.getPosition());
        shader.setUniform("lightDir", light.getDirection());
        shader.setUniform("lightColor", light.getColor());
        shader.setUniform("lightIntensity", light.getIntensity());

        int count = Math.min(pointLightCache.size(), MAX_POINT_LIGHTS);
        shader.setUniform("pointLightCount", count);
        for (int i = 0; i < count; i++) {
            PointLight pl = pointLightCache.get(i);
            String p = POINT_LIGHT_PREFIX[i];
            shader.setUniform(p + "position", pl.getPosition());
            shader.setUniform(p + "color", pl.getColor());
            shader.setUniform(p + "intensity", pl.getIntensity());
            shader.setUniform(p + "constant", pl.getConstant());
            shader.setUniform(p + "linear", pl.getLinear());
            shader.setUniform(p + "quadratic", pl.getQuadratic());
        }

        // 阴影：光空间矩阵 + 深度图（纹理单元 1，与材质的单元 0 分开）
        shader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, depthMap.getTextureId());
        shader.setUniform("shadowMap", 1);
    }

    public void cleanup() {
        if (depthMap != null) {
            depthMap.cleanup();
        }
    }
}
