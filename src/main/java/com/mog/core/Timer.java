package com.mog.core;

/**
 * 高精度计时器：为游戏循环提供每帧耗时（delta time，单位秒）。
 */
public class Timer {

    private double lastTime;

    public Timer() {
        lastTime = now();
    }

    /** 返回距上一次调用的时间差（秒），并重置基准点。 */
    public float getElapsedTime() {
        double t = now();
        float delta = (float) (t - lastTime);
        lastTime = t;
        return delta;
    }

    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }
}
