package com.mog.input;

import com.mog.core.Window;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.DoubleBuffer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 输入处理：轮询式读取键盘/鼠标状态（规避 LWJGL 回调 GC 坑）。
 * 提供：按键持续按下、按键刚按下（边沿检测）、鼠标位置与每帧位移增量。
 * 主循环内零内存分配：光标坐标用常驻 DoubleBuffer，按键用定长数组。
 */
public class InputHandler {

    private static final Logger log = LoggerFactory.getLogger(InputHandler.class);

    private final Window window;

    private final boolean[] keyStates = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] prevKeyStates = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] mouseStates = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] prevMouseStates = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final DoubleBuffer cursorX = BufferUtils.createDoubleBuffer(1);
    private final DoubleBuffer cursorY = BufferUtils.createDoubleBuffer(1);

    private double mouseX;
    private double mouseY;
    private double prevMouseX;
    private double prevMouseY;
    private double deltaX;
    private double deltaY;
    private boolean firstMouse = true;

    public InputHandler(Window window) {
        this.window = window;
    }

    /**
     * 每帧调用：刷新鼠标增量，并把上一帧按键状态归档（供边沿检测）。
     * 窗口失焦时清零一切输入，并标记"首次"，防止重新聚焦时基准点过期产生跳变。
     */
    public void update() {
        glfwGetCursorPos(window.getHandle(), cursorX, cursorY);
        mouseX = cursorX.get(0);
        mouseY = cursorY.get(0);

        if (!window.isFocused()) {
            firstMouse = true;
        }

        if (firstMouse) {
            // 第一帧只建立基准点，不产生位移
            prevMouseX = mouseX;
            prevMouseY = mouseY;
            firstMouse = false;
            deltaX = 0;
            deltaY = 0;
        } else {
            deltaX = mouseX - prevMouseX;
            deltaY = mouseY - prevMouseY;
            prevMouseX = mouseX;
            prevMouseY = mouseY;
        }

        System.arraycopy(keyStates, 0, prevKeyStates, 0, keyStates.length);
        System.arraycopy(mouseStates, 0, prevMouseStates, 0, mouseStates.length);

        // 鼠标诊断日志：记录 GLFW 提供的原始位置与增量。
        // 静止时 delta 应恒为 (0.0,0.0)；出现恒定同向分量 = 传感器/驱动/远程桌面漂移
        if (log.isDebugEnabled() && window.isFocused() && !firstMouse) {
            log.debug("[mouse] pos=({},{}) delta=({},{})",
                    String.format("%.1f", mouseX), String.format("%.1f", mouseY),
                    String.format("%.1f", deltaX), String.format("%.1f", deltaY));
        }
    }

    /**
     * 重置鼠标基准点：光标被程序重新定位后（启动、F1 切换捕获）调用，
     * 下一帧 update() 只重建基准、delta 为零，杜绝位置跳变传入相机。
     */
    public void resetMouse() {
        firstMouse = true;
        deltaX = 0;
        deltaY = 0;
    }

    /** 消费本帧滚轮累积量（上滚为正）。 */
    public double consumeScrollY() {
        return window.consumeScrollY();
    }

    /** 按键是否处于按下状态（会记录，供下一帧边沿检测）。窗口失焦时恒为 false。 */
    public boolean isKeyPressed(int key) {
        boolean pressed = window.isFocused()
                && glfwGetKey(window.getHandle(), key) == GLFW_PRESS;
        keyStates[key] = pressed;
        return pressed;
    }

    /** 按键是否"本帧刚按下"（上一帧未按、本帧按下）。 */
    public boolean isKeyJustPressed(int key) {
        boolean pressed = isKeyPressed(key);
        return pressed && !prevKeyStates[key];
    }

    public boolean isEscapePressed() {
        return isKeyPressed(GLFW_KEY_ESCAPE);
    }

    public boolean isMouseButtonDown(int button) {
        boolean down = window.isFocused()
                && glfwGetMouseButton(window.getHandle(), button) == GLFW_PRESS;
        mouseStates[button] = down;
        return down;
    }

    /** 鼠标键是否"本帧刚按下"。 */
    public boolean isMouseButtonJustPressed(int button) {
        boolean down = isMouseButtonDown(button);
        return down && !prevMouseStates[button];
    }

    public double getMouseX() {
        return mouseX;
    }

    public double getMouseY() {
        return mouseY;
    }

    /** 本帧鼠标 X 位移（像素，右为正）。 */
    public double getDeltaX() {
        return deltaX;
    }

    /** 本帧鼠标 Y 位移（GLFW 坐标系下为正，即鼠标下移为正）。 */
    public double getDeltaY() {
        return deltaY;
    }
}
