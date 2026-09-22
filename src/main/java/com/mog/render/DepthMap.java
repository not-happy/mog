package com.mog.render;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.*;

/**
 * 阴影深度图：只含深度附件的 FBO + 深度纹理。
 * 阴影第一遍（光空间深度遍）渲染到这里，第二遍作为普通纹理采样做遮挡比较。
 */
public class DepthMap {

    /** 阴影贴图分辨率：2 的幂；软件渲染器上 1024 是画质/帧率的平衡点 */
    public static final int SIZE = 1024;

    private final int fboId;
    private final int textureId;

    public DepthMap() {
        fboId = glGenFramebuffers();
        textureId = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, textureId);
        // 只分配深度存储，不上传数据（null）
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        // 深度比较用 NEAREST（线性插值深度值无意义）；边缘 CLAMP 防止采样出界
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, textureId, 0);
        // 纯深度渲染：显式声明不绘制/读取任何颜色缓冲
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("阴影深度 FBO 创建失败: 0x" + Integer.toHexString(status));
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** 绑定并准备深度遍渲染：视口切到阴影图尺寸、清空上一帧深度。 */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, SIZE, SIZE);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    public int getTextureId() {
        return textureId;
    }

    public void cleanup() {
        glDeleteTextures(textureId);
        glDeleteFramebuffers(fboId);
    }
}
