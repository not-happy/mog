package com.mog.astro;

/**
 * 终局裁判（乐章终局状态机的判定辅助类；逻辑不进模拟实体，遵守工程规范）。
 *
 * 三种终局对应 GDD 结局矩阵，口径与 ChaoticSpectrumTool 的测量完全一致
 * （游戏尺度 A_IN=10、40 种子实测：行星死亡 52%（坠焚 45/失家 7）、恒星弹射 48%，
 *  局长中位 196 年 [P25 118, P75 322]）：
 *
 *   1. 坠焚 {@link EndingType#PLANET_SCORCHED} — 行星坠入任一恒星（< 0.4×出生半径）
 *   2. 失家 {@link EndingType#PLANET_LOST}     — 行星被弹出系统（距全部恒星 > ESCAPE_DIST）
 *   3. 弹射 {@link EndingType#STAR_EJECTED}    — 任一恒星被弹出系统（乐章解体）
 *
 * 判定优先级与谱扫描相同：先行星后恒星（同一 tick 内首个终局事件成立）。
 * 注意与 EpochClassifier.LOST_DIST(60) 的分工：那是纪元叙事的预警线
 * （行星漂入深空、天空变黑），这里的 ESCAPE_DIST(100) 才是正式终局判定。
 */
public final class FateJudge {

    /** 坠焚系数（×出生半径，与谱扫描 SCORCH_FACTOR 一致） */
    public static final double SCORCH_FACTOR = 0.4;
    /** 逃逸判定距离（与谱扫描一致：max(60, 10×A_IN)，游戏尺度 = 100）。
     *  恒星以系统质心为参照（总动量=0，质心驻留原点附近）；行星以全部恒星为参照。 */
    public static final double ESCAPE_DIST = Math.max(60.0, 10.0 * TripleConfigs.A_IN);

    private FateJudge() {
    }

    /**
     * 判定当前状态的终局事件。
     *
     * @param scorchDist 坠焚阈值（来自 HostTracker.scorchDistance()）
     * @return 终局类型；无终局返回 null
     */
    public static EndingType judge(GravitySimulation sim, double scorchDist) {
        double dMin = Double.MAX_VALUE;
        for (int i = 0; i < sim.getStarCount(); i++) {
            dMin = Math.min(dMin, sim.getPlanetPos().distance(sim.getStarPos(i)));
        }
        if (dMin < scorchDist) {
            return EndingType.PLANET_SCORCHED;
        }
        if (dMin > ESCAPE_DIST) {
            return EndingType.PLANET_LOST;
        }
        if (ejectedStar(sim) >= 0) {
            return EndingType.STAR_EJECTED;
        }
        return null;
    }

    /** 已被弹射的恒星索引（无则 -1；多颗同时满足时取首个）。 */
    public static int ejectedStar(GravitySimulation sim) {
        for (int i = 0; i < sim.getStarCount(); i++) {
            if (sim.getStarPos(i).length() > ESCAPE_DIST) {
                return i;
            }
        }
        return -1;
    }
}
