package com.mog.render;

import org.joml.Matrix4f;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 轨道残影渲染器：多条 LINE_STRIP 轨迹，加色混合 + 深度只读（天体轨迹的发光感）。
 * 顶点格式：pos(3f) + color(4f，alpha 承载年龄渐隐)。
 * 数据由模拟系统每帧重填（setTrail），本类只负责上传与绘制。
 */
public class TrailRenderer {

    public static final int FLOATS_PER_VERTEX = 7;

    private final int trailCount;
    private final int maxPoints;
    private final FloatBuffer[] buffers;
    private final int[] counts;

    private Shader shader;
    private int[] vaos;
    private int[] vbos;
    private final Matrix4f tmpView = new Matrix4f();

    public TrailRenderer(int trailCount, int maxPoints) {
        this.trailCount = trailCount;
        this.maxPoints = maxPoints;
        this.buffers = new FloatBuffer[trailCount];
        this.counts = new int[trailCount];
        for (int i = 0; i < trailCount; i++) {
            buffers[i] = memAllocFloat(maxPoints * FLOATS_PER_VERTEX);
        }
    }

    public void init() {
        shader = Shader.loadFromClasspath("/shaders/line.vert", "/shaders/line.frag");
        vaos = new int[trailCount];
        vbos = new int[trailCount];
        for (int i = 0; i < trailCount; i++) {
            vaos[i] = glGenVertexArrays();
            vbos[i] = glGenBuffers();
            glBindVertexArray(vaos[i]);
            glBindBuffer(GL_ARRAY_BUFFER, vbos[i]);
            glBufferData(GL_ARRAY_BUFFER, (long) maxPoints * FLOATS_PER_VERTEX * 4, GL_DYNAMIC_DRAW);
            int stride = FLOATS_PER_VERTEX * 4;
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 12);
            glBindVertexArray(0);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** 重填一条轨迹（interleaved: pos3+color4，vertexCount 个点）。 */
    public void setTrail(int index, float[] interleaved, int vertexCount) {
        FloatBuffer buf = buffers[index];
        buf.clear();
        buf.put(interleaved, 0, vertexCount * FLOATS_PER_VERTEX);
        buf.flip();
        counts[index] = vertexCount;
    }

    public void render(Camera camera, Matrix4f projection) {
        boolean any = false;
        for (int c : counts) {
            if (c > 1) {
                any = true;
                break;
            }
        }
        if (!any || shader == null) {
            return;
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);  // 加色混合：轨迹发光
        glDepthMask(false);                  // 不写深度
        glDisable(GL_CULL_FACE);

        shader.bind();
        camera.getViewMatrix(tmpView);
        shader.setUniform("view", tmpView);
        shader.setUniform("projection", projection);

        for (int i = 0; i < trailCount; i++) {
            if (counts[i] <= 1) {
                continue;
            }
            glBindBuffer(GL_ARRAY_BUFFER, vbos[i]);
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffers[i]);
            glBindVertexArray(vaos[i]);
            glDrawArrays(GL_LINE_STRIP, 0, counts[i]);
        }
        glBindVertexArray(0);
        shader.unbind();

        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
        }
        if (vaos != null) {
            for (int vao : vaos) {
                glDeleteVertexArrays(vao);
            }
        }
        if (vbos != null) {
            for (int vbo : vbos) {
                glDeleteBuffers(vbo);
            }
        }
        for (FloatBuffer buf : buffers) {
            memFree(buf);
        }
    }
}
