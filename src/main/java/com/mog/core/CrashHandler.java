package com.mog.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

/**
 * 全局异常处理：兜住所有线程的未捕获异常，记录完整现场并提示用户。
 *
 * 三层防线分工：
 *   1. Game.start() 捕获可恢复 Exception —— 记录日志后走 cleanup 优雅退出
 *   2. 本类（UncaughtExceptionHandler）—— 兜住漏网异常与 Error（OOM 等），
 *      覆盖所有线程（含未来新增的资源加载线程、回调线程）
 *   3. JVM shutdown hook —— 覆盖 kill/SIGTERM 等不执行 finally 的退出路径
 *
 * 注意：原生层崩溃（GLFW/OpenGL 驱动 segfault）JVM 无法捕获，
 * 现场在工作目录的 hs_err_pid*.log 中。
 */
public final class CrashHandler {

    private static final Logger log = LoggerFactory.getLogger(CrashHandler.class);

    private CrashHandler() {
    }

    /** 在 main() 最开头调用（早于一切线程创建）。 */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            log.error("未捕获异常 [线程: {}]", thread.getName(), e);
            showErrorDialog(thread.getName(), e);
        });

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> log.info("JVM 关闭（shutdown hook）"), "mog-shutdown-hook"));

        log.info("全局异常处理已安装");
    }

    /** 崩溃对话框：无显示环境静默跳过；对话框自身异常不外泄，避免二次崩溃。 */
    private static void showErrorDialog(String threadName, Throwable e) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = String.format(
                    "线程 [%s] 发生致命错误：%n%n%s: %s%n%n详细信息见 logs/mog-engine.log",
                    threadName, root.getClass().getName(), root.getMessage());
            JOptionPane.showMessageDialog(null, message, "mog-engine 崩溃",
                    JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // 对话框失败不影响异常已记录的事实
        }
    }
}
