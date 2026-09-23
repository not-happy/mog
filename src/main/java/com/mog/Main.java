package com.mog;

import com.mog.core.CrashHandler;
import com.mog.core.Game;

/**
 * 程序入口：先安装全局异常处理，再启动游戏主循环。
 * 启动参数：--cosmos 直接进入宇宙场景（三体模拟沙盘）。
 */
public final class Main {

    public static void main(String[] args) {
        CrashHandler.install();
        new Game().start(args);
    }
}
