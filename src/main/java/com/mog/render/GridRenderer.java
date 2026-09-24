package com.mog.render;

import com.mog.game.TerrainBuilder;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 建造网格线渲染器：贴地 GL_LINES 静态网格（RTS 建造格视觉基准，C3 落位高亮的地基）。
 *
 * 与 {@link TrailRenderer} 的关键差异（勿混抄）：
 *   - 混合：标准 alpha 混合 SRC_ALPHA/ONE_MINUS_SRC_ALPHA（Trail 是加色 ONE——网格线
 *     用加色会在亮地形上洗白）；
 *   - 深度：测试开、写深度开（线被地形正确遮挡；Trail 关深度写）；
 *   - 数据：init 一次上传 GL_STATIC_DRAW（Trail 每帧重填 DYNAMIC）。
 *
 * render() 期间临时改动的 GL 状态清单（begin/end 对称恢复——吸取 HUD 背面剔除事故教训，
 * 线段无绕序可言，全局 GL_CULL_FACE 会把它整条吃掉）：
 *   1. GL_BLEND       关 -> 开（结束恢复关）
 *   2. GL_CULL_FACE   开 -> 关（结束恢复开）
 *   3. blendFunc      设为 alpha 混合（关 BLEND 后无残留影响）
 *   4. 绑定 VAO/shader（结束解绑）
 *
 * 顶点格式复用 line.vert/line.frag：pos(3f) + color(4f)，每格一段、逐段贴地形高度。
 */
public class GridRenderer {

    /** 顶点格式与 TrailRenderer 相同：pos3 + color4 */
    private static final int FLOATS_PER_VERTEX = 7;
    /** 普通格线颜色（冷灰绿，不抢地形的戏） */
    private static final float LINE_R = 0.5f;
    private static final float LINE_G = 0.6f;
    private static final float LINE_B = 0.55f;
    /** 普通格线 / 每 8 格加亮线的透明度 */
    private static final float ALPHA = 0.28f;
    private static final float ALPHA_MAJOR = 0.56f;
    /** 网格线抬离地形的高度（米）：防与地形 z-fighting */
    private static final float Y_OFFSET = 0.03f;

    private final int cells;
    private final float cellSize;
    private final float heightScale;
    private final long seed;

    private Shader shader;
    private int vao;
    private int vbo;
    private int vertexCount;
    private final Matrix4f tmpView = new Matrix4f();

    public GridRenderer(int cells, float cellSize, float heightScale, long seed) {
        this.cells = cells;
        this.cellSize = cellSize;
        this.heightScale = heightScale;
        this.seed = seed;
    }

    /** 组装全部线段顶点并一次上传（此后每帧零分配、单 draw call）。 */
    public void init() {
        shader = Shader.loadFromClasspath("/shaders/line.vert", "/shaders/line.frag");

        // 每轴 (cells+1) 条线、每条拆 cells 段贴地形、每段 2 顶点
        vertexCount = 2 * (cells + 1) * cells * 2;
        FloatBuffer buf = memAllocFloat(vertexCount * FLOATS_PER_VERTEX);
        float half = cells * cellSize / 2f;
        for (int axis = 0; axis < 2; axis++) {
            for (int line = 0; line <= cells; line++) {
                float w = -half + line * cellSize;
                float alpha = (line % 8 == 0) ? ALPHA_MAJOR : ALPHA;
                for (int seg = 0; seg < cells; seg++) {
                    float t0 = -half + seg * cellSize;
                    float t1 = t0 + cellSize;
                    if (axis == 0) {   // 沿 X 的线（z 固定）
                        putVertex(buf, t0, w, alpha);
                        putVertex(buf, t1, w, alpha);
                    } else {           // 沿 Z 的线（x 固定）
                        putVertex(buf, w, t0, alpha);
                        putVertex(buf, w, t1, alpha);
                    }
                }
            }
        }
        buf.flip();

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
        int stride = FLOATS_PER_VERTEX * 4;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 12);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        memFree(buf);
    }

    private void putVertex(FloatBuffer buf, float x, float z, float alpha) {
        float y = TerrainBuilder.heightAt(x, z, heightScale, seed) + Y_OFFSET;
        buf.put(x).put(y).put(z).put(LINE_R).put(LINE_G).put(LINE_B).put(alpha);
    }

    public void render(Camera camera, Matrix4f projection) {
        if (shader == null || vao == 0) {
            return;
        }
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);

        shader.bind();
        camera.getViewMatrix(tmpView);
        shader.setUniform("view", tmpView);
        shader.setUniform("projection", projection);
        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 0, vertexCount);
        glBindVertexArray(0);
        shader.unbind();

        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
    }
}
