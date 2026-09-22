package com.mog.ui;

import com.mog.render.Shader;
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
 * 文本渲染器（immediate-mode HUD）：每帧把文本展开为屏幕空间四边形，
 * 动态批进一个 VBO 单次 draw call 完成——UI 文本量级的标准做法。
 *
 * 用法：begin(w,h) -> drawText(...)×N -> end()
 * 坐标系：屏幕像素，原点左上，y 向下（正交投影 setOrtho(0,w,h,0)）。
 * 渲染状态：自行关闭深度测试、开启混合，end() 恢复。
 */
public class TextRenderer {

    /** 顶点格式：pos(2f) + uv(2f) + color(4f) */
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int MAX_QUADS = 2048;
    private static final int MAX_VERTICES = MAX_QUADS * 6;

    private final FontAtlas font;
    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer buffer;
    private final Matrix4f proj = new Matrix4f();
    private int vertexCount;

    public TextRenderer(FontAtlas font) {
        this.font = font;
        this.shader = Shader.loadFromClasspath("/shaders/ui_text.vert", "/shaders/ui_text.frag");
        this.buffer = memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);

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
        vertexCount = 0;
        buffer.clear();
        proj.setOrtho(0, screenW, screenH, 0, -1, 1);

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setUniform("proj", proj);
        shader.setUniform("fontAtlas", 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, font.getTextureId());
    }

    /**
     * 绘制一行文本。
     *
     * @param x     左边距（像素）
     * @param baseY 基线位置（像素，y 向下坐标系）
     * @param text  文本（支持已烘焙进图集的任意字符）
     * @param scale 缩放（1.0 = 烘焙像素高度）
     */
    public void drawText(float x, float baseY, String text, float scale,
                         float r, float g, float b, float a) {
        float penX = x;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            FontAtlas.Glyph glyph = font.getGlyph(cp);
            if (glyph == null) {
                penX += 8f * scale; // 未烘焙字符：占位前进
                continue;
            }
            if (glyph.w() > 0 && glyph.h() > 0) {
                if (vertexCount + 6 > MAX_VERTICES) {
                    return; // 批容量保护（HUD 不可能触达）
                }
                float x0 = penX + glyph.offsetX() * scale;
                float y0 = baseY + glyph.offsetY() * scale;
                float x1 = x0 + glyph.w() * scale;
                float y1 = y0 + glyph.h() * scale;
                // 两个三角形 = 6 顶点
                pushVertex(x0, y0, glyph.u0(), glyph.v0(), r, g, b, a);
                pushVertex(x1, y0, glyph.u1(), glyph.v0(), r, g, b, a);
                pushVertex(x1, y1, glyph.u1(), glyph.v1(), r, g, b, a);
                pushVertex(x0, y0, glyph.u0(), glyph.v0(), r, g, b, a);
                pushVertex(x1, y1, glyph.u1(), glyph.v1(), r, g, b, a);
                pushVertex(x0, y1, glyph.u0(), glyph.v1(), r, g, b, a);
            }
            penX += glyph.advanceX() * scale;
        }
    }

    private void pushVertex(float x, float y, float u, float v,
                            float r, float g, float b, float a) {
        buffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
        vertexCount++;
    }

    public void end() {
        if (vertexCount > 0) {
            buffer.flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
        shader.unbind();
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    public FontAtlas getFont() {
        return font;
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        memFree(buffer);
    }
}
