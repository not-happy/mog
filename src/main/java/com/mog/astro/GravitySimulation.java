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

    private final int starCount = 3;
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

        // 双星内部：相对速度 √(G(mA+mB)/a_b) = √(2/4)，各分一半
        double vBin = Math.sqrt(2.0 / 4.0) / 2;        // 0.3536
        // 外轨（偏心）：a=12, e=0.5，近日点 q=a(1-e)=6，
        // 近日点相对速度 v_peri = √(GM(1+e)/q) = √(3×1.5/6)
        double vPeriRel = Math.sqrt(3.0 * 1.5 / 6.0);  // 0.8660
        double vC = vPeriRel * 2.0 / 3.0;              // 0.5774（C，+Z）
        double vBary = -vPeriRel / 3.0;                // -0.2887（双星质心，-Z，动量守恒）

        // 位置：双星质心 (-2,0,0)（A -2 / B +2 分列），C 在 (+4,0,0)（近日点）
        setStarRaw(0, -4, 0, 0, vBary - vBin);        // A
        setStarRaw(1, 0, 0, 0, vBary + vBin);          // B
        setStarRaw(2, 4, 0, 0, vC);                    // C

        // 微扰：混沌的种子（对位置与速度同时施加）
        double eps = 0.02;
        for (int i = 0; i < 3; i++) {
            pos[i].mul(1 + (rnd.nextDouble() - 0.5) * eps,
                    1,
                    1 + (rnd.nextDouble() - 0.5) * eps);
            vel[i].mul(1 + (rnd.nextDouble() - 0.5) * eps,
                    1,
                    1 + (rnd.nextDouble() - 0.5) * eps);
        }

        // 行星：恒星 A 的 r=1 圆轨道（年 = 2π 时间单位）
        double r = PLANET_BIRTH_RADIUS;
        planetPos.set(pos[0].x + r, 0, pos[0].z);
        double vCirc = Math.sqrt(G * mass[0] / r);   // = 1.0
        planetVel.set(vel[0].x, 0, vel[0].z + vCirc);

        time = 0;
    }

    private void setStarRaw(int i, double x, double y, double z, double vz) {
        pos[i].set(x, y, z);
        vel[i].set(0, 0, vz);
    }

    /** KDK Leapfrog 单步。 */
    public void step(double dt) {
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
        // 星-星
        for (int i = 0; i < starCount; i++) {
            for (int j = i + 1; j < starCount; j++) {
                d.set(pos[j]).sub(pos[i]);
                double r2 = d.lengthSquared();
                double inv = 1.0 / (r2 * Math.sqrt(r2)); // 1/r³
                accBuf[i].fma(G * mass[j] * inv, d);
                accBuf[j].fma(-G * mass[i] * inv, d);
            }
        }
        // 星-行星（测试粒子只受力）
        for (int i = 0; i < starCount; i++) {
            d.set(pos[i]).sub(planetPos);
            double r2 = Math.max(d.lengthSquared(), 0.01); // 软化防奇点
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
