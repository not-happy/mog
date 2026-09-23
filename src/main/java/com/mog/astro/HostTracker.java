package com.mog.astro;

/**
 * 宿主星追踪器（"易天"事件的判定辅助类；逻辑不进模拟实体，遵守工程规范）。
 *
 * 宿主 = 行星当前环绕的恒星（最近距离口径）。易主采用滞回判定：
 * 新宿主距离需 &lt; 0.75×现宿主距离才承认捕获，防近距交会期间反复抖动。
 * 口径与 ChaoticSpectrumTool 的测量完全一致——游戏尺度 40 种子实测
 * 易天供给 0.4 次/局长，即由此滞回规则测得。
 *
 * 出生半径在 t=0 采样一次（历法锚点轨道 r=∛(G·m_host)≈1），
 * 作为坠焚阈值（0.4×出生半径）与温度基线的口径来源。
 */
public final class HostTracker {

    /** 易主滞回系数：新宿主距离 < 0.75×现宿主距离才承认捕获 */
    public static final double SWITCH_HYSTERESIS = 0.75;

    private final double birthRadius;
    private int host;
    private int switchCount;

    /** 以 t=0 状态采样初始宿主与出生半径（须在模拟重置后、步进前构造）。 */
    public HostTracker(GravitySimulation sim) {
        host = nearestStar(sim);
        birthRadius = sim.getPlanetPos().distance(sim.getStarPos(host));
    }

    /**
     * 推进一步判定。
     *
     * @return 发生易天时返回新宿主索引；未易天返回 -1
     */
    public int update(GravitySimulation sim) {
        int nearest = nearestStar(sim);
        if (nearest != host) {
            double dNear = sim.getPlanetPos().distance(sim.getStarPos(nearest));
            double dHost = sim.getPlanetPos().distance(sim.getStarPos(host));
            if (dNear < SWITCH_HYSTERESIS * dHost) {
                host = nearest;
                switchCount++;
                return host;
            }
        }
        return -1;
    }

    /** 坠焚判定距离（与谱扫描口径一致：0.4×出生半径）。 */
    public double scorchDistance() {
        return FateJudge.SCORCH_FACTOR * birthRadius;
    }

    public int getHost() {
        return host;
    }

    public double getBirthRadius() {
        return birthRadius;
    }

    public int getSwitchCount() {
        return switchCount;
    }

    private static int nearestStar(GravitySimulation sim) {
        int nearest = 0;
        double dMin = Double.MAX_VALUE;
        for (int i = 0; i < sim.getStarCount(); i++) {
            double d = sim.getPlanetPos().distance(sim.getStarPos(i));
            if (d < dMin) {
                dMin = d;
                nearest = i;
            }
        }
        return nearest;
    }
}
