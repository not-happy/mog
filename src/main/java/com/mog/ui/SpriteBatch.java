package com.mog.ui;

import com.mog.render.Shader;
import com.mog.render.Texture;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 2D 精灵批渲染器（DNF/暗黑式 2D 管线的地基）：
 * 正交投影 + 动态合批——同纹理的精灵累积进一个 VBO，纹理切换时自动 flush，
 * 理想情况下整层 2D 场景只有个位数 draw call。
 *
 * 用法：begin(w,h) -> draw(...)×N -> end()。屏幕坐标系：左上原点，y 向下。
 */
public class SpriteBatch {

    /** 顶点格式：pos(2f) + uv(2f) + color(4f) */
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int MAX_SPRITES = 1024;
    private static final int MAX_VERTICES = MAX_SPRITES * 6;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer buffer;
    private final Matrix4f proj = new Matrix4f();

    private Texture currentTexture;
    private int vertexCount;

    public SpriteBatch() {
        shader = Shader.loadFromClasspath("/shaders/sprite.vert", "/shaders/sprite.frag");
        buffer = memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * 4, GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_VERTEX * 4;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 8);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 16);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public void begin(int screenW, int screenH) {
        proj.setOrtho(0, screenW, screenH, 0, -1, 1);
        buffer.clear();
        vertexCount = 0;
        currentTexture = null;

        glDisable(GL_DEPTH_TEST);
        // 同 TextRenderer：y 向下正交投影的精灵四边形是 NDC 顺时针，必须关背面剔除
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.bind();
        shader.setUniform("proj", proj);
        shader.setUniform("texSampler", 0);
    }

    /** 绘制一个精灵（轴对齐，含色调）。纹理切换会自动 flush 当前批。 */
    public void draw(Texture texture, float x, float y, float w, float h,
                     float r, float g, float b, float a) {
        if (texture != currentTexture) {
            flush();
            currentTexture = texture;
        }
        if (vertexCount + 6 > MAX_VERTICES) {
            flush();
        }
        float x1 = x + w;
        float y1 = y + h;
        pushVertex(x, y, 0, 0, r, g, b, a);
        pushVertex(x1, y, 1, 0, r, g, b, a);
        pushVertex(x1, y1, 1, 1, r, g, b, a);
        pushVertex(x, y, 0, 0, r, g, b, a);
        pushVertex(x1, y1, 1, 1, r, g, b, a);
        pushVertex(x, y1, 0, 1, r, g, b, a);
    }

    private void pushVertex(float x, float y, float u, float v,
                            float r, float g, float b, float a) {
        buffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
        vertexCount++;
    }

    /** 把当前批提交为一个 draw call。 */
    public void flush() {
        if (vertexCount == 0 || currentTexture == null) {
            vertexCount = 0;
            return;
        }
        buffer.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
        glActiveTexture(GL_TEXTURE0);
        currentTexture.bind();
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
        // 复位缓冲继续累积下一批
        buffer.clear();
        vertexCount = 0;
    }

    public void end() {
        flush();
        shader.unbind();
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        memFree(buffer);
    }
}
