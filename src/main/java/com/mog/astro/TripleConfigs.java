package com.mog.astro;

import org.joml.Vector3d;

import java.util.Random;

/**
 * 三重星初始构型工厂：轨道根数/随机采样 -> 笛卡尔状态（位置/速度）。
 *
 * 两个混沌候选家族（S1 谱扫描对比，数据拍板选一个进游戏）：
 *
 * 1. 穿越家族 {@link #crossing}——层级骨架但强制深交：
 *    e_out ∈ [0.55, 0.85] 使第三星近日点 q_out = a_out(1-e_out) 直插双星区域，
 *    每圈外轨回归都是一次 plunging 交会。致密度旋钮 ratio = a_out/a_in。
 *    节奏 = 平静巡航 + 周期性大逼近（戏剧节拍可预期）。
 *
 * 2. 三角家族 {@link #triangle}——Anosova 式随机维里化三体：
 *    三星随机布球 + 维里化速度，从第一圈起互相纠缠，
 *    弹射时标 ~10-100 交会时标，寿命重尾（黄金乐章的物理来源）。
 *    尺度旋钮 scale（动力学时标 ∝ scale^1.5 -> 真实时间标定）。
 *
 * 行星：深轨道出生（宿主引力主导区内），随机宿主、随机相位/轨道面。
 * 第一轮扫描（行星 r=1, e_out 0.3-0.7）的教训：r=1 贴 Holman-Wiegert 临界，
 * 行星死于双星摄动而非三星混沌；e_out 太保守导致 Mardling 判据下多数种子实为准稳定层级。
 */
public final class TripleConfigs {

    /** 内双星半长轴（系统尺度单位）。
     *  测量档 4.0 -> 游戏档 10.0：动力学时标 ×(10/4)^1.5≈3.95（拍板点1：整体放大标定真实时间）。
     *  游戏尺度 40 种子实测：局长中位 196 年 [P25 118, P75 322]，
     *  默认倍速 x0.5 下中位局长 ≈41 真实分钟（GDD 目标窗口 30-60 分钟） */
    public static final double A_IN = 10.0;
    /** 游戏默认致密度（拍板点1：穿越家族 ratio=4.0）。
     *  游戏尺度实测：终局 = 行星死亡 52%（坠焚 45/失家 7）/ 恒星弹射 48%，
     *  近距交会 7.0 次/局（plunging 期集中爆发），易天 0.4 次/局 */
    public static final double RATIO_GAME_DEFAULT = 4.0;
    /** 三角家族行星出生半径系数（×scale）：0.15 实测中位 14-44 年速死（希尔球在交会中反复崩塌），
     *  收紧到 0.08 让行星寿命与乐章解体时标竞争 */
    public static final double PLANET_R_TRI_FACTOR = 0.08;

    private TripleConfigs() {
    }

    /**
     * 穿越家族：层级骨架但强制深交——第三星近日点 q_out = a_out(1-e_out) 直插双星区域。
     * 节奏特征：平静期(外轨巡航) -> 周期性 plunging 交会 -> 交换/弹射。
     * 致密度旋钮 ratio = a_out/a_in；e_out ∈ [0.55, 0.85] 保证 q_out ≤ ~2×a_in。
     * 行星出生半径 = 历法锚点 ∛(G·m_host)（周期恰 2π = 1 游戏年）。
     *
     * @param rnd   种子化随机源
     * @param ratio 致密度旋钮 a_out/a_in（2.5=每圈必搅，5=温和扰动；游戏档 4.0）
     */
    public static TripleState crossing(Random rnd, double ratio) {
        return buildHier(rnd, ratio, 0.55 + rnd.nextDouble() * 0.30, 0);
    }

    /**
     * 三角家族（Anosova 式随机维里化三体）：三星随机布于半径 scale 的球内，
     * 速度按维里定理缩放（T = -β·PE/2），总动量归零。
     * 从第一圈起三星互相纠缠——教科书式的混沌三体，弹射时间 ~10-100 交会时标，
     * 寿命呈重尾分布（黄金乐章的物理来源）。
     *
     * @param rnd   种子化随机源
     * @param scale 系统尺度（动力学时标 ∝ scale^1.5，真实时间标定的旋钮）
     * @param beta  维里系数（1.0=标准维里化；&lt;1 更冷更束缚更长寿）
     */
    public static TripleState triangle(Random rnd, double scale, double beta) {
        TripleState st = new TripleState();
        double mTot = 0;
        for (int i = 0; i < 3; i++) {
            st.mass[i] = 0.9 + rnd.nextDouble() * 0.2;
            mTot += st.mass[i];
        }

        // 位置：球内均匀采样，最小两两间距 > 0.4×scale（拒绝采样，避免开局即奇点交会）
        for (int attempt = 0; attempt < 100; attempt++) {
            for (int i = 0; i < 3; i++) {
                st.starPos[i].set(randomInSphere(rnd, scale));
            }
            Vector3d com = centerOfMass(st);
            for (int i = 0; i < 3; i++) {
                st.starPos[i].sub(com);
            }
            if (minPairDist(st) > 0.4 * scale) {
                break;
            }
        }

        // 势能（含软化）-> 维里目标动能 T = -β·PE/2
        double pe = 0;
        double soft2 = GravitySimulation.SOFTENING * GravitySimulation.SOFTENING;
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                double r = Math.sqrt(st.starPos[j].distanceSquared(st.starPos[i]) + soft2);
                pe -= GravitySimulation.G * st.mass[i] * st.mass[j] / r;
            }
        }
        double tTarget = -beta * pe / 2;

        // 速度：高斯采样（σ_i ∝ 1/√m_i）-> 总动量归零 -> 动能缩放到 T_target
        for (int i = 0; i < 3; i++) {
            double sig = 1.0 / Math.sqrt(st.mass[i]);
            st.starVel[i].set(rnd.nextGaussian() * sig, rnd.nextGaussian() * sig, rnd.nextGaussian() * sig);
        }
        Vector3d pTot = new Vector3d();
        for (int i = 0; i < 3; i++) {
            pTot.add(new Vector3d(st.starVel[i]).mul(st.mass[i]));
        }
        pTot.mul(1.0 / mTot); // 质心速度
        for (int i = 0; i < 3; i++) {
            st.starVel[i].sub(pTot);
        }
        double tActual = 0;
        for (int i = 0; i < 3; i++) {
            tActual += 0.5 * st.mass[i] * st.starVel[i].lengthSquared();
        }
        double k = Math.sqrt(tTarget / Math.max(tActual, 1e-12));
        for (int i = 0; i < 3; i++) {
            st.starVel[i].mul(k);
        }

        // 行星：随机宿主，深轨道 r = 0.15×scale，轨道面随机
        int host = rnd.nextInt(3);
        double r = PLANET_R_TRI_FACTOR * scale;
        Vector3d n = new Vector3d(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1);
        if (n.lengthSquared() < 1e-9) {
            n.set(0, 1, 0);
        }
        n.normalize();
        // 面内正交基：取与 n 夹角足够的参考轴，u = normalize(n × ref)，w = n × u
        Vector3d ref = Math.abs(n.x) < 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0);
        Vector3d u = new Vector3d(n).cross(ref).normalize();
        Vector3d w = new Vector3d(n).cross(u);
        double phi = rnd.nextDouble() * 2 * Math.PI;
        Vector3d dir = new Vector3d(u).mul(Math.cos(phi)).add(new Vector3d(w).mul(Math.sin(phi)));
        Vector3d tan = new Vector3d(n).cross(dir);
        double vCirc = Math.sqrt(GravitySimulation.G * st.mass[host] / r);
        st.planetPos.set(st.starPos[host]).add(new Vector3d(dir).mul(r));
        st.planetVel.set(st.starVel[host]).add(tan.mul(vCirc));

        return st;
    }

    private static Vector3d randomInSphere(Random rnd, double radius) {
        while (true) {
            Vector3d v = new Vector3d(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1);
            if (v.lengthSquared() <= 1.0) {
                return v.mul(radius);
            }
        }
    }

    private static Vector3d centerOfMass(TripleState st) {
        Vector3d com = new Vector3d();
        double mTot = 0;
        for (int i = 0; i < 3; i++) {
            com.add(new Vector3d(st.starPos[i]).mul(st.mass[i]));
            mTot += st.mass[i];
        }
        return com.mul(1.0 / mTot);
    }

    private static double minPairDist(TripleState st) {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                m = Math.min(m, st.starPos[i].distance(st.starPos[j]));
            }
        }
        return m;
    }

    /** 穿越/层级共用骨架（e_out 与行星出生半径参数化）。 */
    private static TripleState buildHier(Random rnd, double ratio, double eOut, double planetR) {
        TripleState st = new TripleState();

        // ===== 质量（近等质量 0.9~1.1，质光关系后续派生）=====
        double mTot = 0;
        for (int i = 0; i < 3; i++) {
            st.mass[i] = 0.9 + rnd.nextDouble() * 0.2;
            mTot += st.mass[i];
        }
        double mBin = st.mass[0] + st.mass[1];

        // ===== 内双星（A 绕 B 的相对轨道；随机小偏心率+随机相位）=====
        double eIn = rnd.nextDouble() * 0.15;
        Vector3d rIn = new Vector3d();
        Vector3d vIn = new Vector3d();
        keplerToState(A_IN, eIn, GravitySimulation.G * mBin,
                0, 0,
                rnd.nextDouble() * 2 * Math.PI,   // ω 随机
                rnd.nextDouble() * 2 * Math.PI,   // ν 随机（真近点角）
                rIn, vIn);

        // ===== 外轨（C 相对双星质心；深交偏心 + 随机倾角/相位）=====
        double aOut = ratio * A_IN;
        double inc = Math.toRadians(rnd.nextDouble() * 80.0);    // 0~80°
        if (rnd.nextBoolean()) {
            inc = -inc;                                          // 逆行也允许（混沌无偏好）
        }
        Vector3d rOut = new Vector3d();
        Vector3d vOut = new Vector3d();
        keplerToState(aOut, eOut, GravitySimulation.G * mTot,
                inc,
                rnd.nextDouble() * 2 * Math.PI,   // Ω 随机
                rnd.nextDouble() * 2 * Math.PI,   // ω 随机
                rnd.nextDouble() * 2 * Math.PI,   // ν 随机
                rOut, vOut);

        // ===== 分配到三个天体（系统质心=原点，总动量=0）=====
        // 双星质心相对系统质心：-(m_C/m_Tot)·rOut；C：+(m_Bin/m_Tot)·rOut
        double kBin = st.mass[2] / mTot;      // 双星质心偏移系数（负侧）
        double kC = mBin / mTot;              // C 偏移系数
        double kA = st.mass[1] / mBin;        // A 在双星内的反质量比
        double kB = st.mass[0] / mBin;

        st.starPos[0].set(rIn).mul(-kA).add(new Vector3d(rOut).mul(-kBin));
        st.starPos[1].set(rIn).mul(kB).add(new Vector3d(rOut).mul(-kBin));
        st.starPos[2].set(rOut).mul(kC);
        st.starVel[0].set(vIn).mul(-kA).add(new Vector3d(vOut).mul(-kBin));
        st.starVel[1].set(vIn).mul(kB).add(new Vector3d(vOut).mul(-kBin));
        st.starVel[2].set(vOut).mul(kC);

        // ===== 行星：随机宿主（0 或 1，双星成员），深圆轨道，相位随机 =====
        int host = rnd.nextInt(2);
        Vector3d hp = st.starPos[host];
        Vector3d hv = st.starVel[host];
        // planetR<=0 -> 历法锚点模式：r = ∛(G·m_host)，轨道周期恰为 2π = 1 游戏年
        double r = planetR > 0 ? planetR : Math.cbrt(GravitySimulation.G * st.mass[host]);
        // 轨道面 = 内双星面：法线 n = normalize(rIn × vIn)
        Vector3d n = new Vector3d(rIn).cross(vIn);
        if (n.lengthSquared() < 1e-12) {
            n.set(0, 1, 0);
        }
        n.normalize();
        // 面内正交基：u = normalize(rIn)，w = n × u
        Vector3d u = new Vector3d(rIn).normalize();
        Vector3d w = new Vector3d(n).cross(u);
        double phi = rnd.nextDouble() * 2 * Math.PI;
        Vector3d dir = new Vector3d(u).mul(Math.cos(phi)).add(new Vector3d(w).mul(Math.sin(phi)));
        Vector3d tan = new Vector3d(n).cross(dir);                 // 顺行切向
        double vCirc = Math.sqrt(GravitySimulation.G * st.mass[host] / r);
        st.planetPos.set(hp).add(new Vector3d(dir).mul(r));
        st.planetVel.set(hv).add(tan.mul(vCirc));

        return st;
    }

    /**
     * 开普勒轨道根数 -> 相对位置/速度（标准二体）。
     * 旋转次序 R = Rz(Ω)·Rx(i)·Rz(ω)，轨道面基准 = XY 平面（右手系，任意世界轴约定均可，
     * 混沌构型没有优先平面）。
     *
     * @param a   半长轴
     * @param e   偏心率
     * @param mu  G·(m1+m2)
     * @param inc 倾角
     * @param om  升交点经度 Ω
     * @param w   近心点角 ω
     * @param nu  真近点角 ν
     */
    static void keplerToState(double a, double e, double mu,
                              double inc, double om, double w, double nu,
                              Vector3d outPos, Vector3d outVel) {
        double p = a * (1 - e * e);
        double r = p / (1 + e * Math.cos(nu));
        // 轨道面内
        double xo = r * Math.cos(nu);
        double yo = r * Math.sin(nu);
        double s = Math.sqrt(mu / p);
        double vxo = -s * Math.sin(nu);
        double vyo = s * (e + Math.cos(nu));
        // Rz(ω)
        double cw = Math.cos(w), sw = Math.sin(w);
        double x1 = cw * xo - sw * yo;
        double y1 = sw * xo + cw * yo;
        double vx1 = cw * vxo - sw * vyo;
        double vy1 = sw * vxo + cw * vyo;
        // Rx(i)
        double ci = Math.cos(inc), si = Math.sin(inc);
        double x2 = x1, y2 = ci * y1, z2 = si * y1;
        double vx2 = vx1, vy2 = ci * vy1, vz2 = si * vy1;
        // Rz(Ω)
        double cO = Math.cos(om), sO = Math.sin(om);
        outPos.set(cO * x2 - sO * y2, sO * x2 + cO * y2, z2);
        outVel.set(cO * vx2 - sO * vy2, sO * vx2 + cO * vy2, vz2);
    }
}
