package com.mog.astro;

import org.joml.Vector3d;

import java.util.Random;

/**
 * 三体引力模拟（restricted 4 体：3 恒星 + 1 无质量行星测试粒子）。
 *
 * 数值方案：
 *   - Leapfrog KDK（辛积分器）：长期能量守恒远优于 RK4/欧拉，轨道模拟标配
 *   - double 精度：float32 的累积误差会让轨道在几分钟内失真
 *   - 固定步长：由 A1 固定时间步长基建驱动，倍速 = 每帧多步
 *
 * 初始构型：弱层级三重星（近距双星 A+B 间距 4，偏心第三星 C：a=12/e=0.5，共面顺行）
 *   + 种子随机微扰。行星生于双星成员 A 的 r=1.0 圆轨道。
 *
 * 为什么不用 8 字形周期解：Monte-Carlo 实测 60/60 种子在 3.3 年全灭
 *   （8 字形每周期必有近距交会，行星希尔球周期性崩塌，确定性坠焚）。
 *   圆外轨层级构型则 >4775 年全存活（太稳，没有戏剧性）。
 *   偏心外轨是平衡点：C 每次近日点回归（"大逼近周期" ≈24 行星年）注入一记
 *   强摄动，混沌按外轨节拍累积——纪元 drama 以百年尺度展开。
 *
 * 时间单位制（游戏历法基准）：
 *   行星年 = 2π√(r³/GM) = 2π ≈ 6.2832 时间单位（出生轨道 r=1, M=1）
 *   外轨周期（"大曜轮"）= 2π√(12³/3) ≈ 151 时间单位 ≈ 24 行星年
 */
public class GravitySimulation {

    public static final double G = 1.0;
    /** 行星出生轨道半径（时间单位制锚点：1 游戏年 = 2π 时间单位） */
    public static final double PLANET_BIRTH_RADIUS = 1.0;
    /** 1 游戏年对应的模拟时间单位 */
    public static final double TIME_UNITS_PER_YEAR =
            2 * Math.PI * Math.sqrt(PLANET_BIRTH_RADIUS * PLANET_BIRTH_RADIUS * PLANET_BIRTH_RADIUS / G);
    /**
     * Plummer 软化长度：全部两体引力用 r²+ε² 防近距相遇时力爆炸
     * （固定步长积分器在近距会失准甚至产生非物理弹射/NaN）。
     * ε=0.05 时 r≥1 的轨道力学误差 <0.3%，游戏尺度下可忽略。
     */
    public static final double SOFTENING = 0.05;
    /** 自适应子步的安全系数（dt_safe = ETA·r_ij/v_rel_ij；0.0005 = 收敛性验证档） */
    private static final double ETA = 0.0005;
    /** 单步子步数上限（性能护栏：极深交会时宁可略失准也不拖垮帧预算） */
    private static final int MAX_SUBSTEPS = 512;

    private final int starCount = 3;
    /** 恒星质量（种子随机 0.9~1.1；质光关系 L=m^3.5 驱动显示亮度与纪元温度模型） */
    private final double[] mass = {1, 1, 1};
    private final Vector3d[] pos = new Vector3d[3];
    private final Vector3d[] vel = new Vector3d[3];
    private final Vector3d[] acc = new Vector3d[3];
    private final Vector3d[] accBuf = new Vector3d[3];

    /** 行星：无质量测试粒子（受三星引力，不反作用） */
    private final Vector3d planetPos = new Vector3d();
    private final Vector3d planetVel = new Vector3d();
    private final Vector3d planetAcc = new Vector3d();

    private double time;
    private final long seed;

    public GravitySimulation(long seed) {
        this.seed = seed;
        for (int i = 0; i < 3; i++) {
            pos[i] = new Vector3d();
            vel[i] = new Vector3d();
            acc[i] = new Vector3d();
            accBuf[i] = new Vector3d();
        }
        reset(seed);
    }

    /**
     * 重置为弱层级三重星构型（轨道面 = XZ 平面，Y-up 世界）：
     *   双星 A(-4,0,0)/B(0,0,0) 绕双星质心 (-2,0,0)，相对圆轨速度 √(2/4)
     *   第三星 C：偏心外轨 a=12, e=0.5，初始在近日点 (4,0,0)——
     *     每次近日点回归给双星一记强摄动（"大逼近周期" ≈ 24 行星年），
     *     混沌沿外轨周期累积，这是纪元戏剧性的引擎
     *   行星生于 A 的 r=1 圆轨道（希尔半径 ≈2.2 的稳定区内）
     *
     * 平衡性实测（Monte-Carlo 60 种子）：
     *   8字形构型    -> 中位寿命 3.3 年（每周期必近距交会，太快）
     *   圆外轨 a=18 -> >4775 年全存活（太稳）
     *   偏心外轨     -> 目标中位寿命 300-600 年（待验证）
     */
    public void reset(long seed) {
        Random rnd = new Random(seed);

        // ===== 恒星质量（种子随机）=====
        for (int i = 0; i < 3; i++) {
            mass[i] = 0.9 + rnd.nextDouble() * 0.2;   // 0.9~1.1（L=m^3.5 ∈ [0.69,1.46]）
        }
        double mBin = mass[0] + mass[1];
        double mTot = mBin + mass[2];

        // ===== 轨道根数（互倾层级三重星，Kozai 驱动的长寿混沌）=====
        // 稳定性设计（文献判据 + MonteCarloTool 实测）：
        //   - 行星轨道 < Holman-Wiegert 临界 0.27×a_in（对双星伴星长期稳定）
        //   - a_out/a_in ≈ 7 略高于 Mardling 临界 ≈6.9（初期稳定，不早解体）
        //   - 倾角跨 Kozai 临界角 39.2°：高倾角宇宙 e_out 被泵高 -> 延迟剧变；
        //     低倾角宇宙平静长寿 -> "黄金纪元"jackpot 池
        //   历法锚点：行星轨道半径 r=∛(G·m_A) => 周期恒等于 2π，与质量/种子无关
        final double aIn = 4.0;                               // 双星半长轴（固定）
        final double aOut = 28.0 + rnd.nextDouble() * 14.0;   // 28~42
        final double eOut = 0.05 + rnd.nextDouble() * 0.25;   // 0.05~0.30
        final double inc = Math.toRadians(30.0 + rnd.nextDouble() * 50.0); // 30°~80°
        final double qOut = aOut * (1 - eOut);                // 外轨近日点距离

        // ===== 质心布局（按质量比分配，系统质心 = 原点，总动量 = 0）=====
        // C 在近日点（沿 +X，绕 X 轴的倾角旋转不改变 X 轴上的点）
        double dC = qOut * mBin / mTot;          // C 到系统质心
        double dBin = qOut * mass[2] / mTot;     // 双星质心到系统质心（-X 侧）
        double dA = aIn * mass[1] / mBin;        // A 到双星质心（反质量比）
        double dB = aIn * mass[0] / mBin;        // B 到双星质心

        // 速度：外轨近日点相对速度 √(GM(1+e)/q)，按动量分配到 C 与双星质心
        double vPeriRel = Math.sqrt(G * mTot * (1 + eOut) / qOut);
        double vC = vPeriRel * mBin / mTot;
        double vBaryCom = vPeriRel * mass[2] / mTot;
        // C 速度方向：轨道面内 ⊥ 位矢（+Z），绕 X 轴倾斜 inc -> (0, -sin i, cos i)×vC
        double vCy = -vC * Math.sin(inc);
        double vCz = vC * Math.cos(inc);
        // 双星质心速度：与 C 反向
        double vBaryY = vBaryCom * Math.sin(inc);
        double vBaryZ = -vBaryCom * Math.cos(inc);
        // 双星内部相对圆轨速度 √(G·mBin/a_in)，按反质量比分配，沿 ±Z（双星面 = XZ）
        double vBinRel = Math.sqrt(G * mBin / aIn);
        double vAInt = vBinRel * mass[1] / mBin;
        double vBInt = vBinRel * mass[0] / mBin;

        setStar(0, -dBin - dA, 0, 0, 0, vBaryY, vBaryZ - vAInt);  // A
        setStar(1, -dBin + dB, 0, 0, 0, vBaryY, vBaryZ + vBInt);  // B
        setStar(2, dC, 0, 0, 0, vCy, vCz);                        // C（倾角在速度分量中）

        // 微扰：混沌的种子（三维位置与速度同时施加，幅度随种子浮动）
        double eps = 0.005 + rnd.nextDouble() * 0.01;   // 0.005~0.015
        for (int i = 0; i < 3; i++) {
            pos[i].mul(1 + (rnd.nextDouble() - 0.5) * eps,
                    1 + (rnd.nextDouble() - 0.5) * eps,
                    1 + (rnd.nextDouble() - 0.5) * eps);
            vel[i].mul(1 + (rnd.nextDouble() - 0.5) * eps,
                    1 + (rnd.nextDouble() - 0.5) * eps,
                    1 + (rnd.nextDouble() - 0.5) * eps);
        }

        // 行星：A 的圆轨道，半径 r=∛(G·m_A) 保证周期恒为 2π（历法锚点）
        double r = Math.cbrt(G * mass[0]);
        planetPos.set(pos[0].x + r, pos[0].y, pos[0].z);
        double vCirc = Math.sqrt(G * mass[0] / r);
        planetVel.set(vel[0].x, vel[0].y, vel[0].z + vCirc);

        time = 0;
    }

    private void setStar(int i, double x, double y, double z,
                         double vx, double vy, double vz) {
        pos[i].set(x, y, z);
        vel[i].set(vx, vy, vz);
    }

    /**
     * 重置为穿越家族混沌构型（层级骨架+强制深交，见 {@link TripleConfigs#crossing}）。
     */
    public void resetCrossing(long seed, double ratio) {
        applyState(TripleConfigs.crossing(new Random(seed), ratio));
    }

    /**
     * 重置为三角家族混沌构型（随机维里化三体，见 {@link TripleConfigs#triangle}）。
     */
    public void resetTriangle(long seed, double scale, double beta) {
        applyState(TripleConfigs.triangle(new Random(seed), scale, beta));
    }

    /** 当前行星到最近恒星的距离（工具/玩法层判定宿主用）。 */
    public double planetNearestDist() {
        double dMin = Double.MAX_VALUE;
        for (int i = 0; i < starCount; i++) {
            dMin = Math.min(dMin, planetPos.distance(pos[i]));
        }
        return dMin;
    }

    /** 应用外部构型工厂生成的初始状态快照。 */
    private void applyState(TripleState st) {
        for (int i = 0; i < 3; i++) {
            mass[i] = st.mass[i];
            pos[i].set(st.starPos[i]);
            vel[i].set(st.starVel[i]);
        }
        planetPos.set(st.planetPos);
        planetVel.set(st.planetVel);
        time = 0;
    }

    /**
     * 三恒星系统的总机械能（含 Plummer 软化势能；行星无质量不计入）。
     * 守恒性 = 积分器精度的诊断量：|E/E0 - 1| 应在 1e-6 以下。
     */
    public double totalEnergy() {
        double ke = 0;
        for (int i = 0; i < starCount; i++) {
            ke += 0.5 * mass[i] * vel[i].lengthSquared();
        }
        double pe = 0;
        Vector3d d = new Vector3d();
        double soft2 = SOFTENING * SOFTENING;
        for (int i = 0; i < starCount; i++) {
            for (int j = i + 1; j < starCount; j++) {
                double r = Math.sqrt(d.set(pos[j]).sub(pos[i]).lengthSquared() + soft2);
                pe -= G * mass[i] * mass[j] / r;
            }
        }
        return ke + pe;
    }

    /** KDK Leapfrog 单步（外层：交会期自适应子步细分）。
     *  致密混沌构型下恒星近距相遇时固定步长会失准（产生假弹射），
     *  子步数按 dt_safe = ETA·r_min/v_max 估计，封顶 MAX_SUBSTEPS 防性能塌方。 */
    public void step(double dt) {
        int n = substeps(dt);
        double h = dt / n;
        for (int k = 0; k < n; k++) {
            leapfrogKDK(h);
        }
    }

    /** 自适应子步估计：逐对取 r_ij/v_rel_ij 最小值约束单步轨道角（相对速度口径，深交会更准）。 */
    private int substeps(double dt) {
        double dtSafe = Double.MAX_VALUE;
        Vector3d dv = new Vector3d();
        for (int i = 0; i < starCount; i++) {
            for (int j = i + 1; j < starCount; j++) {
                double r = pos[i].distance(pos[j]);
                double v = dv.set(vel[i]).sub(vel[j]).length();
                dtSafe = Math.min(dtSafe, ETA * r / Math.max(v, 1e-12));
            }
            double r = pos[i].distance(planetPos);
            double v = dv.set(vel[i]).sub(planetVel).length();
            dtSafe = Math.min(dtSafe, ETA * r / Math.max(v, 1e-12));
        }
        int n = (int) Math.ceil(dt / Math.max(dtSafe, 1e-12));
        return Math.max(1, Math.min(MAX_SUBSTEPS, n));
    }

    private void leapfrogKDK(double dt) {
        computeAccelerations();
        // Kick(半)
        for (int i = 0; i < starCount; i++) {
            vel[i].fma(dt * 0.5, acc[i]);
        }
        planetVel.fma(dt * 0.5, planetAcc);
        // Drift
        for (int i = 0; i < starCount; i++) {
            pos[i].fma(dt, vel[i]);
        }
        planetPos.fma(dt, planetVel);
        // Kick(半)
        computeAccelerations();
        for (int i = 0; i < starCount; i++) {
            vel[i].fma(dt * 0.5, acc[i]);
        }
        planetVel.fma(dt * 0.5, planetAcc);
        time += dt;
    }

    private void computeAccelerations() {
        for (int i = 0; i < starCount; i++) {
            accBuf[i].set(0, 0, 0);
        }
        planetAcc.set(0, 0, 0);
        Vector3d d = new Vector3d();
        double soft2 = SOFTENING * SOFTENING;
        // 星-星（Plummer 软化：近距相遇时力有界，固定步长积分不失准）
        for (int i = 0; i < starCount; i++) {
            for (int j = i + 1; j < starCount; j++) {
                d.set(pos[j]).sub(pos[i]);
                double r2 = d.lengthSquared() + soft2;
                double inv = 1.0 / (r2 * Math.sqrt(r2)); // 1/(r²+ε²)^{3/2}
                accBuf[i].fma(G * mass[j] * inv, d);
                accBuf[j].fma(-G * mass[i] * inv, d);
            }
        }
        // 星-行星（测试粒子只受力）
        for (int i = 0; i < starCount; i++) {
            d.set(pos[i]).sub(planetPos);
            double r2 = d.lengthSquared() + soft2;
            double inv = 1.0 / (r2 * Math.sqrt(r2));
            planetAcc.fma(G * mass[i] * inv, d);
        }
        for (int i = 0; i < starCount; i++) {
            acc[i].set(accBuf[i]);
        }
    }

    public Vector3d getStarPos(int i) {
        return pos[i];
    }

    public double getMass(int i) {
        return mass[i];
    }

    /** 质光关系：主序星光度 L ∝ m^3.5（纪元温度模型与显示亮度用）。 */
    public double getLuminosity(int i) {
        return Math.pow(mass[i], 3.5);
    }

    public Vector3d getStarVel(int i) {
        return vel[i];
    }

    public Vector3d getPlanetPos() {
        return planetPos;
    }

    public Vector3d getPlanetVel() {
        return planetVel;
    }

    public int getStarCount() {
        return starCount;
    }

    public double getTime() {
        return time;
    }

    public long getSeed() {
        return seed;
    }
}
