package com.mog.astro;

import org.joml.Vector3d;

/**
 * 纪元分类器：从三体几何状态派生纪元类型（游戏"天气系统"的唯一真相源）。
 *
 * 物理模型：行星表面温度 T = Σ Lᵢ/dᵢ²（光度归一为 1）。
 * 阈值为可调参数（平衡性旋钮集中在顶部常量区）。
 * 判定优先级：三曜凌空 > 烈曜 > 寒曜 > 掠曜 > 序曜 > 乱曜（默认）。
 */
public class EpochClassifier {

    // ===== 平衡性参数（模拟单位：恒星间距 ~10-20，行星轨道 r=1.5）=====
    /** 灼热阈值：T 超过即烈曜 */
    private static final double SCORCH_T = 0.9;
    /** 严寒阈值：T 低于即寒曜 */
    private static final double FREEZE_T = 0.05;
    /** 序曜判定：唯一近星距离上限 */
    private static final double ORDER_NEAR_DIST = 3.5;
    /** 序曜判定：其余恒星的最小安全距离 */
    private static final double ORDER_FAR_DIST = 6.0;
    /** 掠曜判定：接近速率阈值（距离变化率，单位/时间）与预警距离 */
    private static final double FLYBY_APPROACH_RATE = 2.0;
    private static final double FLYBY_DIST = 9.0;
    /** 三曜凌空：从行星看两两恒星夹角阈值（弧度，~26°） */
    private static final double SYZYGY_ANGLE = 0.45;

    private final Vector3d tmpA = new Vector3d();
    private final Vector3d tmpB = new Vector3d();
    private final double[] dist = new double[3];

    /** 对当前模拟状态分类。 */
    public Epoch classify(GravitySimulation sim) {
        Vector3d p = sim.getPlanetPos();
        int n = sim.getStarCount();

        // 距离与温度
        double temp = 0;
        int nearest = 0;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            dist[i] = p.distance(sim.getStarPos(i));
            double d2 = Math.max(dist[i] * dist[i], 0.01);
            temp += 1.0 / d2; // L=1
            if (dist[i] < nearestDist) {
                nearestDist = dist[i];
                nearest = i;
            }
        }

        EpochType type;
        if (isSyzygy(sim, p)) {
            type = EpochType.SYZYGY;
        } else if (temp > SCORCH_T) {
            type = EpochType.SCORCH;
        } else if (temp < FREEZE_T) {
            type = EpochType.FREEZE;
        } else if (isFlyby(sim, p)) {
            type = EpochType.FLYBY;
        } else if (isOrderly(sim, nearest, nearestDist)) {
            type = EpochType.ORDER_YAO;
        } else {
            type = EpochType.CHAOS_YAO;
        }
        return new Epoch(type, temp, nearest, nearestDist);
    }

    /** 三曜凌空：从行星看，三颗恒星两两夹角都小于阈值。 */
    private boolean isSyzygy(GravitySimulation sim, Vector3d p) {
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                tmpA.set(sim.getStarPos(i)).sub(p).normalize();
                tmpB.set(sim.getStarPos(j)).sub(p).normalize();
                if (tmpA.angle(tmpB) > SYZYGY_ANGLE) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 掠曜：某恒星正在高速逼近（径向速度为负且速率超阈值，距离在预警圈内）。 */
    private boolean isFlyby(GravitySimulation sim, Vector3d p) {
        Vector3d pv = sim.getPlanetVel();
        for (int i = 0; i < 3; i++) {
            if (dist[i] > FLYBY_DIST) {
                continue;
            }
            tmpA.set(sim.getStarPos(i)).sub(p);          // 行星 -> 恒星
            double d = tmpA.length();
            tmpA.normalize();
            tmpB.set(sim.getStarVel(i)).sub(pv);          // 相对速度
            double radialRate = tmpB.dot(tmpA);           // >0 远离，<0 逼近
            if (radialRate < -FLYBY_APPROACH_RATE && d > 1.0) {
                return true;
            }
        }
        return false;
    }

    /** 序曜：唯一恒星在宜居近距内，其余都足够远。 */
    private boolean isOrderly(GravitySimulation sim, int nearest, double nearestDist) {
        if (nearestDist > ORDER_NEAR_DIST) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (i != nearest && dist[i] < ORDER_FAR_DIST) {
                return false;
            }
        }
        return true;
    }
}
