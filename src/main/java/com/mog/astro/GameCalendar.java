package com.mog.astro;

/**
 * 游戏历法：模拟时间 -> 文明纪年换算。
 *
 * 定义（时间单位制的锚点）：
 *   1 游戏年 = 行星出生轨道周期 = 2π√(r³/GM) = 2π 模拟时间单位（r=1, M=1）
 *   1 年 = 360 日（十进制友好，方便 UI 与配表）
 *   默认模拟倍速 1.25 下：1 年 ≈ 5 真实秒
 *
 * 叙事约定：历法基于"出生之年"的天文周期固定不变——即使后来轨道混沌、
 * 母星易主，文明仍用这把旧尺子丈量时间（历法与真实天文脱钩，正是
 * 三体式世界观的痛点之一，可在剧情中利用）。
 */
public final class GameCalendar {

    public static final int DAYS_PER_YEAR = 360;

    private GameCalendar() {
    }

    /** 模拟时间 -> 纪年（从 1 开始）。 */
    public static int yearOf(double simTime) {
        return (int) (simTime / GravitySimulation.TIME_UNITS_PER_YEAR) + 1;
    }

    /** 模拟时间 -> 年内日（1~360）。 */
    public static int dayOf(double simTime) {
        double years = simTime / GravitySimulation.TIME_UNITS_PER_YEAR;
        double frac = years - Math.floor(years);
        return (int) (frac * DAYS_PER_YEAR) + 1;
    }

    /** 格式化：「227年 第145日」。 */
    public static String format(double simTime) {
        return String.format("%d年 第%d日", yearOf(simTime), dayOf(simTime));
    }

    /** 格式化年数（带小数）：「227.4 年」，用于寿命/时长统计。 */
    public static String formatYears(double simTime) {
        return String.format("%.1f 年", simTime / GravitySimulation.TIME_UNITS_PER_YEAR);
    }
}
