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

    // ===== 平衡性参数（穿越构型游戏尺度 A_IN=10：行星 r≈1 绕双星成员，伴星距 ~8.5-11.5，
    //       第三星 6~49 周期性深交；出生温度基线 T≈1.07 与旧尺度一致（历法锚点轨道）=====
    /** 灼热阈值：T 超过即烈曜（出生基线 1.07，宿主星 d<0.75 时触发） */
    private static final double SCORCH_T = 1.8;
    /** 严寒阈值：T 低于即寒曜（宿主星 d>1.5 时触发） */
    private static final double FREEZE_T = 0.45;
    /** 序曜判定：唯一近星距离上限（系于出生半径 ~1，不随系统尺度缩放） */
    private static final double ORDER_NEAR_DIST = 1.8;
    /** 序曜判定：其余恒星的最小安全距离（双星伴星距 ~10 恒安全——
     *  序曜期 = 平静巡航期的管理窗口，只有第三星 plunging 侵入才会打破） */
    private static final double ORDER_FAR_DIST = 2.5;
    /** 掠曜判定：接近速率阈值（距离变化率，单位/时间）。
     *  速度尺度 ∝ 系统尺度^-0.5：A_IN 4->10 后由 1.5 缩至 0.9（×√(4/10)） */
    private static final double FLYBY_APPROACH_RATE = 0.9;
    /** 掠曜预警距离：0.8×A_IN，覆盖第三星深交段（q_out ∈ [6,18]） */
    private static final double FLYBY_DIST = 8.0;
    /** 三曜凌空：从行星看两两恒星夹角阈值（弧度，~26°） */
    private static final double SYZYGY_ANGLE = 0.45;
    /**
     * 三曜凌空的距离条件：三颗恒星全部逼近到此距离内才算"凌空"（0.6×A_IN）。
     * 没有此条件时，行星绕双星成员公转每年都会与伴星视觉成列——凌空沦为日常。
     * 加上它后，凌空 = 第三星也杀到近前的真·三星汇聚（终局级灾难前兆）。
     */
    private static final double SYZYGY_DIST = 6.0;
    /**
     * 失家预警：行星与全部恒星的距离超过此值即『失家深空』纪元（叙事预警线）。
     * 游戏尺度下随远星至远日点的束缚行星最远可达 ~50，60 仍有裕量防误判。
     * 正式失家终局由 {@link FateJudge#ESCAPE_DIST}(=100) 判定，两者分工：
     * 60 起天空变黑进入倒计时演出，100 才冻结乐章触发结算。
     */
    private static final double LOST_DIST = 60.0;

    private final Vector3d tmpA = new Vector3d();
    private final Vector3d tmpB = new Vector3d();
    private final double[] dist = new double[3];

    /** 对当前模拟状态分类。 */
    public Epoch classify(GravitySimulation sim) {
        Vector3d p = sim.getPlanetPos();
        int n = sim.getStarCount();

        // 距离与温度（质光关系 L=m^3.5：重星更亮更危险）
        double temp = 0;
        int nearest = 0;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            dist[i] = p.distance(sim.getStarPos(i));
            double d2 = Math.max(dist[i] * dist[i], 0.01);
            temp += sim.getLuminosity(i) / d2;
            if (dist[i] < nearestDist) {
                nearestDist = dist[i];
                nearest = i;
            }
        }

        EpochType type;
        if (nearestDist > LOST_DIST) {
            type = EpochType.LOST;           // 弹射逃逸优先级最高（终局事件）
        } else if (isSyzygy(sim, p)) {
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

    /** 三曜凌空：三颗恒星全部近距逼近，且从行星看两两夹角都小于阈值。 */
    private boolean isSyzygy(GravitySimulation sim, Vector3d p) {
        for (int i = 0; i < 3; i++) {
            if (dist[i] > SYZYGY_DIST) {
                return false; // 有恒星远在天边，谈不上"凌空"
            }
        }
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
