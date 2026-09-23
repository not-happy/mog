package com.mog.astro.tools;

import com.mog.astro.GravitySimulation;
import com.mog.astro.TripleConfigs;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 混沌构型谱扫描工具 v2（S1 测量，无头运行）。
 *
 * 双家族对比扫描：
 *   - 穿越家族 crossing(ratio)：层级骨架+强制深交（e_out 0.55~0.85），扫 ratio 谱
 *   - 三角家族 triangle(scale)：随机维里化三体（Anosova 式），扫 scale 谱
 *
 * 测量四组分布供拍板：
 *   1. 行星寿命（=局长候选A）：坠焚 / 失家
 *   2. 乐章寿命（=局长候选B）：恒星弹射
 *   3. 宿主易主频率（"易天"事件的物理供给）
 *   4. 恒星近距交会频率（大灾变节拍供给）
 * 附带积分精度验证：总能量漂移 |ΔE/E0| < 1e-6。
 *
 * 判定阈值随家族尺度自适应：坠焚 = 0.4×行星出生半径；交会/逃逸按系统尺度缩放。
 * 时间报告单位：标准年 = 2π 模拟时间单位（跨家族可比；游戏内年另标定）。
 *
 * 运行：java -cp target/mog-engine-*.jar com.mog.astro.tools.ChaoticSpectrumTool
 */
public final class ChaoticSpectrumTool {

    // ===== 扫描参数 =====
    private static final double[] CROSSING_RATIOS = {3.5, 4.0, 4.5, 5.0};
    private static final double[] TRIANGLE_SCALES = {};
    private static final double TRI_BETA = 1.0;
    private static final int SEEDS_PER_CONFIG = 40;
    private static final double CUTOFF_YEARS = 3000;

    // ===== 数值参数 =====
    private static final double DT = 0.002;
    private static final double SCORCH_FACTOR = 0.4;     // 坠焚 = 0.4×出生半径（随家族自适应）
    private static final double HOST_SWITCH_HYST = 0.75; // 易主滞回：新星距离 < 0.75×现宿主
    private static final int CHECK_EVERY = 4;            // 每 N 步检查一次（积分主导成本）

    private ChaoticSpectrumTool() {
    }

    /** 家族类型。 */
    private enum Family { CROSSING, TRIANGLE }

    /** 单局测量结果。 */
    private static final class RunResult {
        double planetDeathYear = Double.POSITIVE_INFINITY; // 行星死亡年（未死=Inf）
        boolean deathScorch;                               // true=坠焚 false=失家
        double ejectYear = Double.POSITIVE_INFINITY;       // 恒星弹射年（乐章终结）
        double endYear;                                    // 实际积分截止年（首个终局事件）
        int hostSwitches;                                  // 宿主易主次数
        int closeEvents;                                   // 星-星近距交会次数
        double maxDrift;                                   // 最大能量漂移
        boolean planetFirst;                               // 行星先死（vs 恒星先弹射）
        boolean survived;                                  // 双存到截止
    }

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        System.out.printf("混沌构型谱扫描 v2: 穿越 x%d + 三角 x%d, 各 %d 种子, 截止 %.0f 标准年%n",
                CROSSING_RATIOS.length, TRIANGLE_SCALES.length, SEEDS_PER_CONFIG, CUTOFF_YEARS);
        System.out.println("（标准年 = 2π 时间单位; 局长 = min(行星死亡, 恒星弹射)）");

        for (int ri = 0; ri < CROSSING_RATIOS.length; ri++) {
            double ratio = CROSSING_RATIOS[ri];
            List<RunResult> runs = new ArrayList<>();
            for (int s = 0; s < SEEDS_PER_CONFIG; s++) {
                long seed = 917_000L + ri * 10_000L + s;
                runs.add(runOne(seed, Family.CROSSING, ratio));
            }
            report(String.format("穿越 ratio=%.1f (a_out=%.0f)", ratio, ratio * TripleConfigs.A_IN),
                    runs);
        }
        for (int ti = 0; ti < TRIANGLE_SCALES.length; ti++) {
            double scale = TRIANGLE_SCALES[ti];
            List<RunResult> runs = new ArrayList<>();
            for (int s = 0; s < SEEDS_PER_CONFIG; s++) {
                long seed = 555_000L + ti * 10_000L + s;
                runs.add(runOne(seed, Family.TRIANGLE, scale));
            }
            report(String.format("三角 scale=%.0f (beta=%.1f)", scale, TRI_BETA), runs);
        }
        System.out.printf("%n扫描完成, 耗时 %.1f 秒%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static RunResult runOne(long seed, Family family, double param) {
        GravitySimulation sim = new GravitySimulation(seed);
        if (family == Family.CROSSING) {
            sim.resetCrossing(seed, param);
        } else {
            sim.resetTriangle(seed, param, TRI_BETA);
        }
        double e0 = sim.totalEnergy();

        // 出生半径（t=0 行星-宿主距离）-> 自适应坠焚阈值
        int host = nearestStar(sim);
        double birthRadius = sim.getPlanetPos().distance(sim.getStarPos(host));
        double scorchDist = SCORCH_FACTOR * birthRadius;
        // 系统尺度 -> 交会/逃逸阈值（穿越: 交会=0.5×a_in; 三角: 交会=0.5×scale, 逃逸=10×scale）
        double sysScale = (family == Family.CROSSING) ? TripleConfigs.A_IN : param;
        double closeDist = 0.5 * sysScale;
        double escapeDist = Math.max(60.0, 10.0 * sysScale);

        RunResult r = new RunResult();
        boolean[] pairAbove = {true, true, true}; // 三对恒星是否在交会阈值之上
        double cutoffTime = CUTOFF_YEARS * GravitySimulation.TIME_UNITS_PER_YEAR;

        Vector3d p = new Vector3d();
        int step = 0;
        while (sim.getTime() < cutoffTime) {
            sim.step(DT);
            if (++step % CHECK_EVERY != 0) {
                continue;
            }
            double year = sim.getTime() / GravitySimulation.TIME_UNITS_PER_YEAR;

            // ---- 能量漂移 ----
            r.maxDrift = Math.max(r.maxDrift, Math.abs(sim.totalEnergy() / e0 - 1));

            // ---- 行星状态 ----
            p.set(sim.getPlanetPos());
            double dMin = Double.MAX_VALUE;
            int nearest = 0;
            for (int i = 0; i < 3; i++) {
                double d = p.distance(sim.getStarPos(i));
                if (d < dMin) {
                    dMin = d;
                    nearest = i;
                }
            }
            if (dMin < scorchDist) {
                r.planetDeathYear = year;
                r.deathScorch = true;
                r.endYear = year;
                r.planetFirst = true;
                break;
            }
            if (dMin > escapeDist) {
                r.planetDeathYear = year;
                r.deathScorch = false;
                r.endYear = year;
                r.planetFirst = true;
                break;
            }

            // ---- 宿主易主（滞回：新宿主距离需 < 0.75×现宿主距离）----
            double curHostDist = p.distance(sim.getStarPos(host));
            if (nearest != host && dMin < HOST_SWITCH_HYST * curHostDist) {
                host = nearest;
                r.hostSwitches++;
            }

            // ---- 恒星近距交会（下穿计数 + 1.25x 滞回复位）----
            for (int i = 0, k = 0; i < 3; i++) {
                for (int j = i + 1; j < 3; j++, k++) {
                    double d = sim.getStarPos(i).distance(sim.getStarPos(j));
                    if (pairAbove[k] && d < closeDist) {
                        pairAbove[k] = false;
                        r.closeEvents++;
                    } else if (!pairAbove[k] && d > closeDist * 1.25) {
                        pairAbove[k] = true;
                    }
                }
            }

            // ---- 恒星弹射（乐章终结）----
            boolean ejected = false;
            for (int i = 0; i < 3; i++) {
                if (sim.getStarPos(i).length() > escapeDist) {
                    ejected = true;
                    break;
                }
            }
            if (ejected) {
                r.ejectYear = year;
                r.endYear = year;
                r.planetFirst = false;
                break;
            }
        }
        if (Double.isInfinite(r.planetDeathYear) && Double.isInfinite(r.ejectYear)) {
            r.survived = true;
            r.endYear = sim.getTime() / GravitySimulation.TIME_UNITS_PER_YEAR;
        }
        return r;
    }

    private static int nearestStar(GravitySimulation sim) {
        int nearest = 0;
        double dMin = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double d = sim.getPlanetPos().distance(sim.getStarPos(i));
            if (d < dMin) {
                dMin = d;
                nearest = i;
            }
        }
        return nearest;
    }

    private static void report(String label, List<RunResult> runs) {
        int n = runs.size();
        int scorch = 0, lost = 0, ejected = 0, survived = 0, planetFirst = 0;
        List<Double> planetLives = new ArrayList<>();
        List<Double> ejectLives = new ArrayList<>();
        List<Double> endLives = new ArrayList<>();
        int switches = 0, closeEvents = 0;
        double endYearsSum = 0, maxDrift = 0;

        for (RunResult r : runs) {
            if (!Double.isInfinite(r.planetDeathYear)) {
                planetLives.add(r.planetDeathYear);
                if (r.deathScorch) scorch++; else lost++;
            }
            if (!Double.isInfinite(r.ejectYear)) {
                ejectLives.add(r.ejectYear);
                ejected++;
            }
            if (r.survived) survived++;
            if (r.planetFirst) planetFirst++;
            switches += r.hostSwitches;
            closeEvents += r.closeEvents;
            endLives.add(r.endYear);
            endYearsSum += r.endYear;
            maxDrift = Math.max(maxDrift, r.maxDrift);
        }

        System.out.printf("%n=== %s ===%n", label);
        System.out.printf("  局长(首个终局事件): 中位 %s 年  [P25 %s, P75 %s]  均值 %.0f 年%n",
                fmt(median(endLives)), fmt(pct(endLives, 25)), fmt(pct(endLives, 75)),
                endYearsSum / n);
        System.out.printf("  行星死亡: %d/%d (坠焚 %d, 失家 %d)%s%n",
                planetLives.size(), n, scorch, lost,
                planetLives.isEmpty() ? "" : String.format("  中位 %s 年", fmt(median(planetLives))));
        System.out.printf("  恒星弹射(乐章终结): %d/%d%s   双存到截止: %d/%d%n",
                ejected, n,
                ejectLives.isEmpty() ? "" : String.format("  中位 %s 年", fmt(median(ejectLives))),
                survived, n);
        System.out.printf("  终局顺序: 行星先死 %d, 恒星先弹射 %d, 未分胜负 %d%n",
                planetFirst, ejected, survived);
        System.out.printf("  宿主易主: 平均 %.1f 次/局长   近距交会: 平均 %.1f 次/局长%n",
                switches / (double) n, closeEvents / (double) n);
        System.out.printf("  能量漂移 max: %.2e  %s%n", maxDrift,
                maxDrift < 1e-6 ? "(精度合格)" : "(!! 精度不足)");
        // 宿主易主/交会按局长归一
        System.out.printf("  [归一] 易主 %.2f 次/100年, 交会 %.2f 次/100年%n",
                switches / Math.max(1e-9, endYearsSum) * 100,
                closeEvents / Math.max(1e-9, endYearsSum) * 100);
    }

    private static double median(List<Double> v) {
        return pct(v, 50);
    }

    private static double pct(List<Double> v, int p) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int idx = (int) Math.round((a.length - 1) * p / 100.0);
        return a[idx];
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.0f", v);
    }
}
