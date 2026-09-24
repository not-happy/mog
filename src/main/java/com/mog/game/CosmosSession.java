package com.mog.game;

import com.mog.astro.EndingType;
import com.mog.astro.Epoch;
import com.mog.astro.EpochClassifier;
import com.mog.astro.EpochType;
import com.mog.astro.FateJudge;
import com.mog.astro.GameCalendar;
import com.mog.astro.GravitySimulation;
import com.mog.astro.HostTracker;
import com.mog.core.event.EpochChangedEvent;
import com.mog.core.event.EventBus;
import com.mog.core.event.HostChangedEvent;
import com.mog.core.event.RunEndedEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * 宇宙会话（Game 级持久，跨场景唯一真源）：三体积分、纪元分类、易天/终局状态、
 * 时间控制（暂停/倍速）与拖尾历史环形缓冲都在这里，生命周期与整局游戏一致。
 *
 * GDD D5 一致性铁律——"宇宙视角看到的三星之舞，就是地表经历的天气"：
 * 玩家在地表时模拟照常推进（Game 固定步长循环里 tick），切回宇宙拖尾包含离场期间的历史。
 *
 * 纯 Java、零 GL/零 ECS 依赖：不引用 World、实体、TrailRenderer——
 * 视图同步（实体 Transform、拖尾顶点组装）由 {@link CosmosViewSystem} 薄壳完成。
 * 实体类不放逻辑的项目规则在这里同样成立：本类是会话/服务，不是实体。
 */
public final class CosmosSession {

    private static final Logger log = LoggerFactory.getLogger(CosmosSession.class);

    /** 积分固定步长（模拟时间单位） */
    public static final double SIM_DT = 0.002;
    /** 单次 tick 最大子步数（防卡顿后的死亡螺旋）。
     *  也决定了倍速的有效上限：SIM_DT×800×60fps = 96 模拟单位/秒 ≈ 15 年/秒 */
    private static final int MAX_STEPS_PER_FRAME = 800;
    /** 倍速上限（调试加速用，正常游玩到不了这么高） */
    private static final double MAX_SPEED = 120.0;
    /** 每条轨迹的环形缓冲容量 */
    public static final int TRAIL_CAP = 2400;
    /** 每 N 个积分步记录一个轨迹点（彗尾式运动拖尾：2400 点×4 步×0.002 = 19.2 时间单位
     *  ≈ 3 游戏年 ≈ 默认倍速下 38 真实秒的运动历史，点距致密贴住天体） */
    private static final int RECORD_EVERY = 4;

    /** 天体拖尾颜色。恒星用 HDR 值（>1，与球体同色系）：头部亮度超过泛光提取阈值
     *  （PostProcessor 0.75 + 软膝），拖尾随球体一起发光——否则细线淹没在恒星自身光晕里。
     *  行星保持 SDR 暗色（不抢恒星的戏）。 */
    public static final float[][] BODY_COLORS = {
            {2.00f, 1.70f, 1.10f},   // 曜一（金黄 HDR）
            {2.00f, 1.10f, 0.60f},   // 曜二（橙红 HDR）
            {1.30f, 1.50f, 2.00f},   // 曜三（蓝白 HDR）
            {0.35f, 0.65f, 0.95f},   // 行星（蓝）
    };

    private final GravitySimulation sim;
    private final EpochClassifier classifier = new EpochClassifier();
    private final EventBus eventBus;
    /** 宿主星追踪（易天事件源；构造时采样 t=0 宿主与出生半径） */
    private final HostTracker hostTracker;

    // 轨迹环形缓冲（SoA）：历史留在会话里，场景切换不丢
    private final float[][] ringX = new float[4][TRAIL_CAP];
    private final float[][] ringY = new float[4][TRAIL_CAP];
    private final float[][] ringZ = new float[4][TRAIL_CAP];
    private final int[] trailCount = new int[4];
    private final int[] trailHead = new int[4];

    private double stepAccum;
    private int stepCounter;
    private Epoch currentEpoch;

    // ===== 乐章终局状态（FateJudge 判定，首个终局事件后冻结物理步进）=====
    private boolean runEnded;
    private EndingType ending;
    private int ejectedStar = -1;

    /** 模拟速度（模拟时间单位/真实秒）与暂停状态（由场景 handleInput 控制）。
     *  默认 0.5：1 游戏年(2π 单位) ≈ 12.6 真实秒；40 种子实测局长中位 196 年 ≈ 41 分钟，
     *  P25-P75 = 25-67 分钟，覆盖 GDD 30-60 分钟目标窗口（拍板点1：整体放大系统尺度标定） */
    private double speed = 0.5;
    private boolean paused;

    /** 正式开局：随机种子 = 一个全新乐章。 */
    public CosmosSession(EventBus eventBus) {
        this(eventBus, new Random().nextLong());
    }

    /** 指定种子开局（冒烟测试复现已知结局用）。 */
    public CosmosSession(EventBus eventBus, long seed) {
        this.eventBus = eventBus;
        this.sim = new GravitySimulation(seed);
        this.hostTracker = new HostTracker(sim);
    }

    /**
     * 推进模拟（Game 固定步长循环每步调用一次，与场景无关——地表期间照常 tick）。
     * 语义与原 CosmosSimSystem.update 的步骤 1/4 完全一致：倍速子步长积分、
     * 逐步易天/终局判定、纪元分类与事件发布；局长标定不漂移。
     */
    public void tick(float deltaTime) {
        // ===== 1. 固定子步长推进积分（乐章终结后停步，供终局演出冻结画面）=====
        if (!paused && !runEnded) {
            stepAccum += speed * deltaTime;
            int steps = (int) (stepAccum / SIM_DT);
            if (steps > MAX_STEPS_PER_FRAME) {
                steps = MAX_STEPS_PER_FRAME;
                stepAccum = 0;
            } else {
                stepAccum -= steps * SIM_DT;
            }
            for (int s = 0; s < steps && !runEnded; s++) {
                sim.step(SIM_DT);
                if (++stepCounter % RECORD_EVERY == 0) {
                    recordTrailPoint();
                }
                checkFate();   // 逐步判定易天/终局（防高倍速下隧穿漏判坠焚）
            }
        }

        // ===== 2. 纪元分类与事件 =====
        Epoch epoch = classifier.classify(sim);
        if (currentEpoch == null || epoch.type() != currentEpoch.type()) {
            eventBus.publish(new EpochChangedEvent(currentEpoch, epoch));
            log.info(String.format("纪元变更: %s -> %s (温度 %.3f, 最近曜 %.2f)",
                    currentEpoch != null ? currentEpoch.type().getDisplayName() : "(初始)",
                    epoch.type().getDisplayName(), epoch.temperature(), epoch.nearestDist()));
            if (epoch.type() == EpochType.LOST) {
                log.warn("母星漂入深空——『失家深空』预警：天空已全黑，"
                        + "距正式失家终局（FateJudge）仅剩漂流倒计时");
            }
        }
        currentEpoch = epoch;
    }

    /**
     * 逐步判定：易天（宿主易主）与乐章终局。
     * 口径与 ChaoticSpectrumTool 测量一致（HostTracker 滞回 / FateJudge 阈值），
     * 保证玩家实际经历的分布 = 40 种子扫描测得的分布。
     */
    private void checkFate() {
        int oldHost = hostTracker.getHost();
        int newHost = hostTracker.update(sim);
        if (newHost >= 0) {
            eventBus.publish(new HostChangedEvent(oldHost, newHost, sim.getTime()));
            log.info("『易天』{}: 行星被曜{}捕获（第 {} 次易主）——文明的天空换了一颗太阳",
                    GameCalendar.yearOf(sim.getTime()), newHost + 1, hostTracker.getSwitchCount());
        }

        EndingType fate = FateJudge.judge(sim, hostTracker.scorchDistance());
        if (fate == null) {
            return;
        }
        runEnded = true;
        ending = fate;
        ejectedStar = fate == EndingType.STAR_EJECTED ? FateJudge.ejectedStar(sim) : -1;
        eventBus.publish(new RunEndedEvent(fate, ejectedStar, sim.getTime(), hostTracker.getSwitchCount()));
        switch (fate) {
            case PLANET_SCORCHED -> log.warn("『乐章终局』{}: 行星坠入恒星焚毁——毁灭结算"
                            + "（传承点结算 -> 星海轮回新种子重开）",
                    GameCalendar.yearOf(sim.getTime()));
            case PLANET_LOST -> log.warn("『乐章终局』{}: 行星被弹出三体系统，冰封远航——毁灭结算"
                            + "（文明冻结 -> 传承点结算 -> 星海轮回新种子重开）",
                    GameCalendar.yearOf(sim.getTime()));
            case STAR_EJECTED -> log.warn("『乐章终局』{}: 曜{} 被弹射离场，三体系统解体——"
                            + "终曲演出 + 逃亡判定（S3 演出层接入）",
                    GameCalendar.yearOf(sim.getTime()), ejectedStar + 1);
        }
    }

    private void recordTrailPoint() {
        for (int i = 0; i < 3; i++) {
            Vector3d p = sim.getStarPos(i);
            putRing(i, (float) p.x, (float) p.y, (float) p.z);
        }
        Vector3d pp = sim.getPlanetPos();
        putRing(3, (float) pp.x, (float) pp.y, (float) pp.z);
    }

    private void putRing(int b, float x, float y, float z) {
        ringX[b][trailHead[b]] = x;
        ringY[b][trailHead[b]] = y;
        ringZ[b][trailHead[b]] = z;
        trailHead[b] = (trailHead[b] + 1) % TRAIL_CAP;
        if (trailCount[b] < TRAIL_CAP) {
            trailCount[b]++;
        }
    }

    // ===== 拖尾读取（视图层 CosmosViewSystem 组装顶点用）=====

    /** 天体 b（0-2 恒星，3 行星）当前拖尾点数。 */
    public int getTrailCount(int body) {
        return trailCount[body];
    }

    /**
     * 把天体拖尾展开为 TrailRenderer 顶点格式（旧 -> 新，每点 7 float：pos3+color3+alpha1），
     * alpha 平方渐隐 = 彗尾式运动拖尾。dst 容量须 >= getTrailCount(body) * 7。
     */
    public void fillTrailVertices(int body, float[] dst) {
        int n = trailCount[body];
        float[] color = BODY_COLORS[body];
        int p = 0;
        for (int k = 0; k < n; k++) {
            int idx = (trailHead[body] - n + k + TRAIL_CAP * 2) % TRAIL_CAP;
            float t = (k + 1) / (float) n;
            float alpha = t * t * 0.9f;   // 头部亮、尾部快速消隐（加色混合下呈彗尾发光）
            dst[p++] = ringX[body][idx];
            dst[p++] = ringY[body][idx];
            dst[p++] = ringZ[body][idx];
            dst[p++] = color[0];
            dst[p++] = color[1];
            dst[p++] = color[2];
            dst[p++] = alpha;
        }
    }

    // ===== 时间控制（场景 handleInput 调用；地表/宇宙共享同一会话）=====

    public void togglePause() {
        paused = !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void multiplySpeed(double factor) {
        setSpeed(speed * factor);
    }

    /** 设置绝对倍速（钳制到 [0.5, MAX_SPEED]）。 */
    public void setSpeed(double newSpeed) {
        speed = Math.max(0.5, Math.min(MAX_SPEED, newSpeed));
    }

    public double getSpeed() {
        return speed;
    }

    // ===== 状态读取（HUD / 结算流程 / 地表天空读取）=====

    public GravitySimulation getSim() {
        return sim;
    }

    public Epoch getCurrentEpoch() {
        return currentEpoch;
    }

    public HostTracker getHostTracker() {
        return hostTracker;
    }

    public boolean isRunEnded() {
        return runEnded;
    }

    /** 终局类型（未终结时为 null）。 */
    public EndingType getEnding() {
        return ending;
    }

    /** 弹射离场的恒星索引（仅 STAR_EJECTED 终局有效，否则 -1）。 */
    public int getEjectedStar() {
        return ejectedStar;
    }
}
