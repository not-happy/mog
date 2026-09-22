package com.mog.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.nio.IntBuffer;

/**
 * GLFW 窗口封装：创建窗口、维护帧缓冲尺寸、处理 vsync 与标题栏 FPS。
 * 不包含任何游戏逻辑。
 */
public class Window {

    private static final Logger log = LoggerFactory.getLogger(Window.class);

    private long handle;
    private final String title;
    private int width;
    private int height;
    private int fbWidth;
    private int fbHeight;
    private final boolean vSync;
    private boolean resized;
    private boolean mouseCaptured;

    public Window(String title, int width, int height, boolean vSync) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.fbWidth = width;
        this.fbHeight = height;
        this.vSync = vSync;
    }

    public void init() {
        // GLFW 错误回调接入日志（替代默认的直接打印 stderr）
        GLFWErrorCallback.create((error, description) ->
                log.error("GLFW 错误 [0x{}]: {}", Integer.toHexString(error),
                        GLFWErrorCallback.getDescription(description))).set();
        if (!glfwInit()) {
            throw new IllegalStateException("无法初始化 GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        // 使用现代 OpenGL（拒绝立即模式等废弃 API）
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new IllegalStateException("创建窗口失败");
        }

        // 帧缓冲尺寸变化回调（高分屏上窗口尺寸 != 像素尺寸）
        glfwSetFramebufferSizeCallback(handle, (window, w, h) -> {
            fbWidth = w;
            fbHeight = h;
            resized = true;
        });

        // 窗口居中
        try (var stack = stackPush()) {
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            glfwGetWindowSize(handle, pw, ph);
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidmode != null) {
                glfwSetWindowPos(handle, (vidmode.width() - pw.get(0)) / 2,
                        (vidmode.height() - ph.get(0)) / 2);
            }
        }

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(vSync ? 1 : 0);
        glfwShowWindow(handle);

        // 关键：必须在 makeContextCurrent 之后调用，否则报 "no current context"
        GL.createCapabilities();

        // 记录图形环境：排查驱动/兼容问题的第一手信息
        log.info("OpenGL 版本: {} | GLSL: {}", glGetString(GL_VERSION), glGetString(GL_SHADING_LANGUAGE_VERSION));
        log.info("渲染器: {} | 厂商: {}", glGetString(GL_RENDERER), glGetString(GL_VENDOR));
        log.info("窗口已创建: {}x{}, vSync={}", width, height, vSync);

        // 启动时把鼠标定位到窗口中心
        centerCursor();
    }

    /** 把光标定位到窗口中心（启动、重新捕获鼠标时调用，消除位置跳变）。 */
    public void centerCursor() {
        glfwSetCursorPos(handle, width / 2.0, height / 2.0);
    }

    /** 处理窗口消息（每帧调用一次）。 */
    public void pollEvents() {
        glfwPollEvents();
    }

    /** 交换前后缓冲（每帧调用一次）。 */
    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public boolean isCloseRequested() {
        return glfwWindowShouldClose(handle);
    }

    /** 消费"窗口已调整大小"标记，返回调整前的状态。 */
    public boolean takeResized() {
        boolean r = resized;
        resized = false;
        return r;
    }

    public void updateTitle(int fps) {
        glfwSetWindowTitle(handle, title + "  |  FPS: " + fps);
    }

    /** 捕获鼠标：隐藏光标并锁定在窗口内（FPS 式视角），false 则恢复正常光标。 */
    public void setMouseCaptured(boolean captured) {
        mouseCaptured = captured;
        glfwSetInputMode(handle, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (captured) {
            centerCursor();
        }
    }

    public boolean isMouseCaptured() {
        return mouseCaptured;
    }

    /** 窗口当前是否拥有键盘焦点（失焦时应暂停输入处理）。 */
    public boolean isFocused() {
        return glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE;
    }

    public long getHandle() {
        return handle;
    }

    public int getFbWidth() {
        return fbWidth;
    }

    public int getFbHeight() {
        return fbHeight;
    }

    public void cleanup() {
        if (handle != NULL) {
            glfwDestroyWindow(handle);
            handle = NULL;
        }
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
    }
}
