package com.mog.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.*;

/**
 * 通用帧缓冲封装：一张颜色纹理 + 可选深度 Renderbuffer。
 * 场景后处理、离屏渲染的基础设施（阴影专用的 DepthMap 是纯深度特化版）。
 * hdr=true 时颜色附件用 RGBA16F 半浮点（保留 >1.0 的亮度，供 HDR 泛光/色调映射）。
 */
public class FrameBuffer {

    private final boolean withDepth;
    private final boolean hdr;
    private int fboId;
    private int colorTextureId;
    private int depthBufferId; // renderbuffer，无深度时为 0
    private int width;
    private int height;

    public FrameBuffer(int width, int height, boolean withDepth) {
        this(width, height, withDepth, false);
    }

    public FrameBuffer(int width, int height, boolean withDepth, boolean hdr) {
        this.withDepth = withDepth;
        this.hdr = hdr;
        create(width, height);
    }

    private void create(int w, int h) {
        this.width = Math.max(w, 1);
        this.height = Math.max(h, 1);

        fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        // 颜色附件：线性过滤（后处理采样需要），HDR 用半浮点存储
        colorTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTextureId);
        if (hdr) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0,
                    GL_RGBA, GL_HALF_FLOAT, (java.nio.ByteBuffer) null);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTextureId, 0);

        // 深度附件：Renderbuffer（只写不采样，比深度纹理省）
        if (withDepth) {
            depthBufferId = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depthBufferId);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBufferId);
        }

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("FBO 创建失败: 0x" + Integer.toHexString(status));
        }
    }

    /** 绑定为渲染目标并切换视口到 FBO 尺寸。 */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, width, height);
    }

    /** 回到默认帧缓冲（屏幕）。 */
    public static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** 窗口尺寸变化时重建（FBO 纹理不能改尺寸，只能销毁重建）。 */
    public void resize(int w, int h) {
        if (w == width && h == height) {
            return;
        }
        delete();
        create(w, h);
    }

    private void delete() {
        if (colorTextureId != 0) {
            glDeleteTextures(colorTextureId);
            colorTextureId = 0;
        }
        if (depthBufferId != 0) {
            glDeleteRenderbuffers(depthBufferId);
            depthBufferId = 0;
        }
        if (fboId != 0) {
            glDeleteFramebuffers(fboId);
            fboId = 0;
        }
    }

    public int getColorTexture() {
        return colorTextureId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void cleanup() {
        delete();
    }
}
