package com.mog.render;

import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.EmitterComponent;
import com.mog.ecs.components.PointLightComponent;
import com.mog.ecs.components.TransformComponent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 粒子引擎：CPU 模拟 + 相机公告板四边形渲染（SoA 数组池，零每帧分配）。
 * 同时是 ECS 系统（update 模拟）与渲染对象（render 需 Game 在场景遍内调用）。
 *
 * 为什么不用 GL_POINTS 点精灵：尺寸有硬件上限、部分驱动（如 WARP/Mesa-d3d12）
 * 实现有缺陷会挂起；公告板四边形走普通三角形管线，处处可靠，也是商业引擎主流做法。
 *
 * 混合模式：加色（SRC_ALPHA, ONE）——火花/魔法类发光粒子的标准配方，配合泛光效果拔群。
 * 深度测试开但深度写入关（粒子互不遮挡且不改深度）。
 */
public class ParticleEngine implements GameSystem {

    private static final int MAX_PARTICLES = 2048;
    private static final float GRAVITY = -2.2f;
    /** 每粒子 = 6 顶点 × 9 float（pos3 + uv2 + color4） */
    private static final int FLOATS_PER_VERTEX = 9;
    private static final int VERTICES_PER_PARTICLE = 6;

    // SoA 粒子池（struct-of-arrays，缓存友好且免对象分配）
    private final float[] px = new float[MAX_PARTICLES];
    private final float[] py = new float[MAX_PARTICLES];
    private final float[] pz = new float[MAX_PARTICLES];
    private final float[] vx = new float[MAX_PARTICLES];
    private final float[] vy = new float[MAX_PARTICLES];
    private final float[] vz = new float[MAX_PARTICLES];
    private final float[] life = new float[MAX_PARTICLES];
    private final float[] maxLife = new float[MAX_PARTICLES];
    private final float[] cr = new float[MAX_PARTICLES];
    private final float[] cg = new float[MAX_PARTICLES];
    private final float[] cb = new float[MAX_PARTICLES];
    private final float[] size = new float[MAX_PARTICLES];
    private int alive;

    /** 每实体的发射欠账（rate × dt 的小数部分累积） */
    private final Map<Integer, Float> spawnAccum = new HashMap<>();
    private final Random random = new Random();

    private Shader shader;
    private int vao;
    private int vbo;
    private FloatBuffer vertexBuf;

    // 公告板基向量（复用，避免每帧分配）
    private final Vector3f bbRight = new Vector3f();
    private final Vector3f bbUp = new Vector3f();
    private final Vector3f bbFwd = new Vector3f();
    private final Matrix4f tmpView = new Matrix4f();

    public void init() {
        shader = Shader.loadFromClasspath("/shaders/particle.vert", "/shaders/particle.frag");
        vertexBuf = memAllocFloat(MAX_PARTICLES * VERTICES_PER_PARTICLE * FLOATS_PER_VERTEX);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_PARTICLES * VERTICES_PER_PARTICLE * FLOATS_PER_VERTEX * 4,
                GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_VERTEX * 4;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 20);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    // ==================== 模拟（ECS 系统） ====================

    @Override
    public void update(World world, float deltaTime) {
        // 1. 发射：遍历发射器实体，按 rate 累积欠账取整发射
        for (int e : world.view(TransformComponent.class, EmitterComponent.class)) {
            EmitterComponent em = world.getComponent(e, EmitterComponent.class);
            float acc = spawnAccum.merge(e, em.getRate() * deltaTime, Float::sum);
            int count = (int) acc;
            if (count > 0) {
                spawnAccum.put(e, acc - count);
                Vector3f pos = world.getComponent(e, TransformComponent.class).getPosition();
                PointLightComponent plc = world.getComponent(e, PointLightComponent.class);
                float r = 1, g = 1, b = 1;
                if (plc != null) {
                    Vector3f c = plc.getLight().getColor();
                    r = c.x; g = c.y; b = c.z;
                }
                for (int i = 0; i < count; i++) {
                    spawn(pos, em, r, g, b);
                }
            }
        }

        // 2. 积分：重力 + 位移 + 寿命；死亡粒子用"末尾交换"移除（保持数组紧凑）
        for (int i = 0; i < alive; i++) {
            life[i] -= deltaTime;
            if (life[i] <= 0) {
                removeAt(i--);
                continue;
            }
            vy[i] += GRAVITY * deltaTime;
            px[i] += vx[i] * deltaTime;
            py[i] += vy[i] * deltaTime;
            pz[i] += vz[i] * deltaTime;
        }
    }

    private void spawn(Vector3f pos, EmitterComponent em, float r, float g, float b) {
        if (alive >= MAX_PARTICLES) {
            return; // 池满：丢弃新粒子（也可改杀最旧）
        }
        int i = alive++;
        px[i] = pos.x; py[i] = pos.y; pz[i] = pos.z;
        // 随机方向（球面均匀）× spread 收窄 + 初速
        float theta = random.nextFloat() * (float) (2 * Math.PI);
        float phi = (float) Math.acos(1 - 2 * random.nextFloat() * em.getSpread());
        float sp = em.getSpeed() * (0.5f + random.nextFloat() * 0.5f);
        vx[i] = (float) (Math.sin(phi) * Math.cos(theta)) * sp;
        vy[i] = (float) Math.cos(phi) * sp * 0.5f + 0.6f * sp; // 略向上喷
        vz[i] = (float) (Math.sin(phi) * Math.sin(theta)) * sp;
        float lt = em.getLifetime() * (0.7f + random.nextFloat() * 0.6f);
        life[i] = lt;
        maxLife[i] = lt;
        cr[i] = r; cg[i] = g; cb[i] = b;
        size[i] = 0.05f + random.nextFloat() * 0.04f;
    }

    private void removeAt(int i) {
        int last = --alive;
        if (i != last) {
            px[i]=px[last]; py[i]=py[last]; pz[i]=pz[last];
            vx[i]=vx[last]; vy[i]=vy[last]; vz[i]=vz[last];
            life[i]=life[last]; maxLife[i]=maxLife[last];
            cr[i]=cr[last]; cg[i]=cg[last]; cb[i]=cb[last];
            size[i]=size[last];
        }
    }

    // ==================== 渲染（场景遍内调用） ====================

    /** 在场景 FBO 内、主遍之后调用：CPU 展开公告板四边形，加色混合一次绘制。 */
    public void render(Camera camera, Matrix4f projection, int viewportHeight) {
        if (alive == 0 || shader == null) {
            return;
        }
        // 公告板基：right 由 yaw 决定，up = right × forward（始终面向相机）
        float yaw = camera.getRotation().y;
        bbRight.set((float) Math.cos(yaw), 0, (float) Math.sin(yaw));
        camera.getForward(bbFwd);
        bbRight.cross(bbFwd, bbUp).normalize();

        vertexBuf.clear();
        for (int i = 0; i < alive; i++) {
            float a = Math.max(life[i] / maxLife[i], 0f); // 透明度随剩余寿命淡出
            float s = size[i];
            // 四角：p ∓ right·s ∓ up·s
            float rx = bbRight.x * s, ry = bbRight.y * s, rz = bbRight.z * s;
            float ux = bbUp.x * s, uy = bbUp.y * s, uz = bbUp.z * s;
            float x = px[i], y = py[i], z = pz[i];
            float r = cr[i], g = cg[i], b = cb[i];
            // 三角形 1: (-1,-1) (1,-1) (1,1)
            pushVertex(x - rx - ux, y - ry - uy, z - rz - uz, -1, -1, r, g, b, a);
            pushVertex(x + rx - ux, y + ry - uy, z + rz - uz, 1, -1, r, g, b, a);
            pushVertex(x + rx + ux, y + ry + uy, z + rz + uz, 1, 1, r, g, b, a);
            // 三角形 2: (-1,-1) (1,1) (-1,1)
            pushVertex(x - rx - ux, y - ry - uy, z - rz - uz, -1, -1, r, g, b, a);
            pushVertex(x + rx + ux, y + ry + uy, z + rz + uz, 1, 1, r, g, b, a);
            pushVertex(x - rx + ux, y - ry + uy, z - rz + uz, -1, 1, r, g, b, a);
        }
        vertexBuf.flip();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);   // 加色混合：发光粒子
        glDepthMask(false);                   // 不写深度（粒子互不遮挡）
        glDisable(GL_CULL_FACE);              // 公告板双面可见，省去绕序顾虑

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuf);
        shader.bind();
        camera.getViewMatrix(tmpView);
        shader.setUniform("view", tmpView);
        shader.setUniform("projection", projection);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, alive * VERTICES_PER_PARTICLE);
        glBindVertexArray(0);
        shader.unbind();

        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private void pushVertex(float x, float y, float z, float u, float v,
                            float r, float g, float b, float a) {
        vertexBuf.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    public int getAliveCount() {
        return alive;
    }

    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
        if (vertexBuf != null) {
            memFree(vertexBuf);
        }
    }
}
