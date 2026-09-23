package com.mog.astro;

import org.joml.Vector3d;

import java.util.Random;

/**
 * 三体引力模拟（ restricted 4 体：3 恒星 + 1 无质量行星测试粒子）。
 *
 * 数值方案：
 *   - Leapfrog KDK（辛积分器）：长期能量守恒远优于 RK4/欧拉，轨道模拟标配
 *   - double 精度：float32 的累积误差会让轨道在几分钟内失真
 *   - 固定步长：由 A1 固定时间步长基建驱动，倍速 = 每帧多步
 *
 * 初始条件：Chenciner-Montgomery "8 字形"三体周期解（G=m=1 精确解）
 *   × 空间缩放 10（速度按 √(1/s) 缩放保持解的有效性）+ 种子随机微扰。
 * 效果：开局为准周期优雅舞蹈，微扰随混沌指数放大，数轨道周期后纪元剧变——
 * 这正是玩法需要的"可读的随机性"。轨道面置于 XZ 平面（Y-up 世界）。
 */
public class GravitySimulation {

    public static final double G = 1.0;
    /** 空间缩放（8 字形解 ×10，恒星间距 ~10-20 场景单位） */
    public static final double SCALE = 10.0;

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

    /** 重置模拟：8 字形解 + 扰动 + 行星入轨。 */
    public void reset(long seed) {
        Random rnd = new Random(seed);
        // 8 字形精确解（G=m=1，轨道面 x-y）：
        //   p1 = (0.97000436, -0.24308753), p2 = -p1, p3 = 0
        //   v3 = (-0.93240737, -0.86473146)/2, v1 = v2 = -v3/2
        // 映射到世界 XZ 平面：解的 (x,y) -> 世界 (x, 0, y)
        double v3x = -0.93240737 / 2, v3z = -0.86473146 / 2;
        double v1x = -v3x / 2, v1z = -v3z / 2;   // = v3/4
        double vScale = Math.sqrt(1.0 / SCALE); // 位置×s 时速度 ×√(1/s) 保持解有效

        placeStar(0, 0.97000436, -0.24308753, v1x, v1z, vScale, rnd);
        placeStar(1, -0.97000436, 0.24308753, v1x, v1z, vScale, rnd);
        placeStar(2, 0, 0, v3x, v3z, vScale, rnd);

        // 行星：恒星 0 的近似圆轨道（r=1.5，位于希尔球 ~6.9 内，暂时稳定；
        // 恒星近距相遇时会被弹射/易主——这正是三体式戏剧性的来源）
        double r = 1.5;
        Vector3d radial = new Vector3d(1, 0, 0);
        planetPos.set(pos[0]).add(radial.x * r, 0, radial.z * r);
        double vCirc = Math.sqrt(G * mass[0] / r);
        Vector3d tangent = new Vector3d(radial.z, 0, -radial.x).normalize();
        planetVel.set(tangent).mul(vCirc).add(vel[0]);

        time = 0;
    }

    private void placeStar(int i, double x8, double y8, double vx8, double vy8,
                           double vScale, Random rnd) {
        double eps = 0.01; // 微扰幅度：混沌的种子
        pos[i].set(x8 * SCALE * (1 + (rnd.nextDouble() - 0.5) * eps),
                0,
                y8 * SCALE * (1 + (rnd.nextDouble() - 0.5) * eps));
        vel[i].set(vx8 * vScale * (1 + (rnd.nextDouble() - 0.5) * eps),
                0,
                vy8 * vScale * (1 + (rnd.nextDouble() - 0.5) * eps));
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
