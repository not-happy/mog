package com.mog.astro.tools;

import com.mog.astro.GameCalendar;
import com.mog.astro.GravitySimulation;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平衡性工具：蒙特卡洛批量模拟，统计文明轨道寿命分布。
 *
 * 运行（无需图形环境）：
 *   java -cp target/mog-engine-*.jar com.mog.astro.tools.MonteCarloTool [样本数]
 *
 * 死因判据（与 EpochClassifier 阈值联动调整）：
 *   - 坠入/掠焚：行星与任一恒星距离 < 0.6（出生轨道 r=1.0 内侧深处）
 *   - 弹射失家：行星与全部恒星距离 > 35（LOST_DIST）
 *
 * 历史实测：
 *   8字形构型        中位 3.3 年（60/60 坠焚）——每周期必近距交会，弃用
 *   层级圆外轨 a=18  >4775 年全存活——太稳无戏剧性，弃用
 *   层级偏心 e=0.5   P25=181 年, 中位 2306 年, 47% 长寿——当前采用
 */
public final class MonteCarloTool {

    private static final double DT = 0.002;
    private static final int CHECK_EVERY = 10;
    private static final double T_MAX = 30000;      // 模拟时间上限（≈4775 年）
    private static final double DEATH_SCORCH_DIST = 0.6;
    private static final double DEATH_LOST_DIST = 60.0;   // 与 EpochClassifier.LOST_DIST 同步（> 最大轨道尺度 48）
    /** 恒星逃逸判定：任一恒星离系统质心（原点，总动量为零）超过此距离 = 系统解体。
     *  恒星逃逸后剩余双星+行星将永远规律运行（混沌引擎熄火），按终局处理。 */
    private static final double STAR_ESCAPE_DIST = 60.0;

    private MonteCarloTool() {
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        List<Double> deathYears = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        int censored = 0;

        for (int s = 0; s < n; s++) {
            GravitySimulation sim = new GravitySimulation(0x9E3779B97F4A7C15L * (s + 1));
            String cause = "存活";
            double tDeath = T_MAX;
            outer:
            for (double t = 0; t < T_MAX; t += DT * CHECK_EVERY) {
                for (int k = 0; k < CHECK_EVERY; k++) {
                    sim.step(DT);
                }
                Vector3d p = sim.getPlanetPos();
                double dmin = Double.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    dmin = Math.min(dmin, p.distance(sim.getStarPos(i)));
                    if (sim.getStarPos(i).length() > STAR_ESCAPE_DIST) {
                        cause = "恒星逃逸(系统解体)";
                        tDeath = sim.getTime();
                        break outer;
                    }
                }
                if (dmin > DEATH_LOST_DIST) {
                    cause = "弹射失家";
                    tDeath = sim.getTime();
                    break outer;
                }
                if (dmin < DEATH_SCORCH_DIST) {
                    cause = "坠入/掠焚";
                    tDeath = sim.getTime();
                    break outer;
                }
            }
            if (tDeath >= T_MAX) {
                censored++;
            }
            deathYears.add(tDeath / GravitySimulation.TIME_UNITS_PER_YEAR);
            causes.add(cause);
        }

        Collections.sort(deathYears);
        System.out.printf("样本 %d 局（%d 局在 %s 内未死）%n",
                n, censored, GameCalendar.formatYears(T_MAX));
        System.out.println("系统剧变寿命分布（行星年）:");
        System.out.printf("  最短   %8.1f 年  (%s)%n", deathYears.get(0), causes.get(0));
        System.out.printf("  P25    %8.1f 年%n", pct(deathYears, 25));
        System.out.printf("  中位数 %8.1f 年%n", pct(deathYears, 50));
        System.out.printf("  P75    %8.1f 年%n", pct(deathYears, 75));
        System.out.printf("  P90    %8.1f 年%n", pct(deathYears, 90));
        System.out.printf("  最长   %8.1f 年%n", deathYears.get(deathYears.size() - 1));
        Map<String, Integer> causeCount = new LinkedHashMap<>();
        for (String c : causes) {
            causeCount.merge(c, 1, Integer::sum);
        }
        System.out.println("结局分布: " + causeCount);
        System.out.printf("参考: 中位寿命折真实时间 ≈ %.0f 分钟（默认倍速 1.25，1 年≈5 秒）%n",
                pct(deathYears, 50) * 5 / 60);
    }

    private static double pct(List<Double> sorted, int p) {
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
