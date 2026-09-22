package com.mog.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * 纹理封装：用 stb_image 解码 PNG/JPG 等图片，上传为 OpenGL 2D 纹理。
 * 图片来源支持三种：classpath 资源、文件系统路径、内存中的压缩图片字节（模型内嵌贴图）。
 * 自动生成 mipmap，采用线性过滤。
 */
public class Texture {

    private int id;
    private int width;
    private int height;

    public Texture(String path) {
        this(path, false);
    }

    /**
     * @param path   classpath 资源路径（以 / 开头）或文件系统路径
     * @param repeat true 时 UV 超出 0~1 会平铺重复（地面等大面积贴图用），false 时钳制到边缘
     */
    public Texture(String path, boolean repeat) {
        ByteBuffer compressed = readToBuffer(path);
        try {
            decodeAndUpload(compressed, repeat);
        } finally {
            memFree(compressed);
        }
    }

    /** 供内部使用的空构造：配合 decodeAndUpload 完成初始化。 */
    private Texture() {
    }

    /**
     * 从内存中的压缩图片数据构建纹理（如 .glb 模型内嵌的 PNG 字节流）。
     * 不取得 compressedImage 的所有权：调用方负责其生命周期，本方法只在解码期间读取。
     */
    public static Texture fromCompressedMemory(ByteBuffer compressedImage, boolean repeat) {
        Texture t = new Texture();
        t.decodeAndUpload(compressedImage, repeat);
        return t;
    }

    /** stb 解码 + GL 上传（核心流程，三种来源共用）。 */
    private void decodeAndUpload(ByteBuffer compressedImage, boolean repeat) {
        ByteBuffer pixels = null;
        try (var stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            // 强制解码为 RGBA（4 通道），stbi_load_from_memory 返回堆外内存
            pixels = stbi_load_from_memory(compressedImage, w, h, channels, 4);
            if (pixels == null) {
                throw new IllegalStateException("图片解码失败: " + stbi_failure_reason());
            }
            width = w.get(0);
            height = h.get(0);

            id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);

            // 过滤与环绕参数
            int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
            glGenerateMipmap(GL_TEXTURE_2D);

            glBindTexture(GL_TEXTURE_2D, 0);
        } finally {
            if (pixels != null) {
                stbi_image_free(pixels);
            }
        }
    }

    /** 绑定到纹理单元 0。 */
    public void bind() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public void unbind() {
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void cleanup() {
        glDeleteTextures(id);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * 读取图片字节到堆外 ByteBuffer（调用方负责 memFree）。
     * 以 / 开头按 classpath 资源查找；否则按文件系统路径读取。
     */
    private static ByteBuffer readToBuffer(String path) {
        try {
            byte[] bytes;
            if (path.startsWith("/")) {
                try (InputStream in = Texture.class.getResourceAsStream(path)) {
                    if (in == null) {
                        throw new IllegalArgumentException("找不到 classpath 资源: " + path);
                    }
                    bytes = in.readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(Path.of(path));
            }
            ByteBuffer buf = memAlloc(bytes.length);
            buf.put(bytes);
            buf.flip();
            return buf;
        } catch (IOException e) {
            throw new IllegalStateException("读取图片失败: " + path, e);
        }
    }
}
