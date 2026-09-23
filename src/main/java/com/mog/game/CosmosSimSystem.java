package com.mog.game;

import com.mog.astro.EndingType;
import com.mog.astro.Epoch;
import com.mog.astro.EpochClassifier;
import com.mog.astro.FateJudge;
import com.mog.astro.GameCalendar;
import com.mog.astro.GravitySimulation;
import com.mog.astro.HostTracker;
import com.mog.core.event.EpochChangedEvent;
import com.mog.core.event.EventBus;
import com.mog.core.event.HostChangedEvent;
import com.mog.core.event.RunEndedEvent;
import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.TransformComponent;
import com.mog.render.TrailRenderer;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 星系模拟系统：以固定子步长推进三体积分（倍速 = 每帧多步），
 * 同步天体实体 Transform、维护轨道残影环形缓冲、实时纪元分类并发布事件。
 *
 * 玩法层职责（S2）：逐步判定易天（HostTracker -> HostChangedEvent）
 * 与乐章终局（FateJudge -> RunEndedEvent，首个终局事件后冻结物理步进），
 * 口径与 ChaoticSpectrumTool 测量一致——玩家经历的分布 = 扫描测得的分布。
 *
 * 模拟与渲染解耦：本系统是 GameSystem（进 World 调度），
 * 残影顶点数组每帧重建后交给 TrailRenderer（渲染由场景在 overlay 阶段触发）。
 */
public class CosmosSimSystem implements GameSystem {

    private static final Logger log = LoggerFactory.getLogger(CosmosSimSystem.class);

    /** 积分固定步长（模拟时间单位） */
    public static final double SIM_DT = 0.002;
    /** 单帧最大子步数（防卡顿后的死亡螺旋）。
     *  也决定了倍速的有效上限：SIM_DT×800×60fps = 96 模拟单位/秒 ≈ 15 年/秒 */
    private static final int MAX_STEPS_PER_FRAME = 800;
    /** 倍速上限（调试加速用，正常游玩到不了这么高） */
    private static final double MAX_SPEED = 120.0;
    /** 每条轨迹的环形缓冲容量 */
    public static final int TRAIL_CAP = 2400;
    /** 每 N 个积分步记录一个轨迹点（外轨周期 ~918 单位，2400 点×200 步×0.002 = 960 单位
     *  ≈ 覆盖一整圈外轨残影——第三星的 plunging 俯冲轨迹完整可见） */
    private static final int RECORD_EVERY = 200;

    /** 天体显示颜色（恒星 >1 = HDR，触发泛光；行星暗色） */
    private static final float[][] BODY_COLORS = {
            {1.00f, 0.85f, 0.55f},   // 曜一（金黄）
            {1.00f, 0.55f, 0.30f},   // 曜二（橙红）
            {0.65f, 0.75f, 1.00f},   // 曜三（蓝白）
            {0.35f, 0.65f, 0.95f},   // 行星（蓝）
    };

    private final GravitySimulation sim;
    private final EpochClassifier classifier = new EpochClassifier();
    private final EventBus eventBus;
    private final int[] starEntities;
    private final int planetEntity;
    private final TrailRenderer trails;
    /** 宿主星追踪（易天事件源；构造时采样 t=0 宿主与出生半径） */
    private final HostTracker hostTracker;

    // 轨迹环形缓冲（SoA）
    private final float[][] ringX = new float[4][TRAIL_CAP];
    private final float[][] ringY = new float[4][TRAIL_CAP];
    private final float[][] ringZ = new float[4][TRAIL_CAP];
    private final int[] trailCount = new int[4];
    private final int[] trailHead = new int[4];
    private final float[][] vertexScratch = new float[4][TRAIL_CAP * TrailRenderer.FLOATS_PER_VERTEX];
    /** 三星连线（三角形 = 三体问题的标志性视觉符号），每帧更新，占用第 5 条轨迹槽 */
    private final float[] triangleVerts = new float[4 * TrailRenderer.FLOATS_PER_VERTEX];

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

    public CosmosSimSystem(GravitySimulation sim, int[] starEntities, int planetEntity,
                           TrailRenderer trails, EventBus eventBus) {
        this.sim = sim;
        this.starEntities = starEntities;
        this.planetEntity = planetEntity;
        this.trails = trails;
        this.eventBus = eventBus;
        this.hostTracker = new HostTracker(sim);
    }

    @Override
    public void update(World world, float deltaTime) {
        // ===== 1. 固定子步长推进积分（乐章终结后停步，场景冻结供终局演出）=====
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

        // ===== 2. 同步天体实体 Transform（double -> float 渲染精度足够）=====
        for (int i = 0; i < starEntities.length; i++) {
            Vector3d p = sim.getStarPos(i);
            TransformComponent t = world.getComponent(starEntities[i], TransformComponent.class);
            t.getPosition().set((float) p.x, (float) p.y, (float) p.z);
        }
        Vector3d pp = sim.getPlanetPos();
        world.getComponent(planetEntity, TransformComponent.class)
                .getPosition().set((float) pp.x, (float) pp.y, (float) pp.z);

        // ===== 3. 重建轨迹顶点（旧 -> 新，alpha 渐隐）=====
        for (int b = 0; b < 4; b++) {
            int n = trailCount[b];
            float[] verts = vertexScratch[b];
            int p = 0;
            for (int k = 0; k < n; k++) {
                int idx = (trailHead[b] - n + k + TRAIL_CAP * 2) % TRAIL_CAP;
                float alpha = (k + 1) / (float) n * 0.85f;
                verts[p++] = ringX[b][idx];
                verts[p++] = ringY[b][idx];
                verts[p++] = ringZ[b][idx];
                verts[p++] = BODY_COLORS[b][0];
                verts[p++] = BODY_COLORS[b][1];
                verts[p++] = BODY_COLORS[b][2];
                verts[p++] = alpha;
            }
            trails.setTrail(b, verts, n);
        }

        // ===== 3b. 三星连线（A->B->C->A 闭合，LINE_STRIP 4 点）=====
        int tp = 0;
        for (int k = 0; k < 4; k++) {
            Vector3d sp = sim.getStarPos(k % 3);
            triangleVerts[tp++] = (float) sp.x;
            triangleVerts[tp++] = (float) sp.y;
            triangleVerts[tp++] = (float) sp.z;
            triangleVerts[tp++] = 0.72f;   // 冷白色、低透明度：几何辅助线不抢戏
            triangleVerts[tp++] = 0.78f;
            triangleVerts[tp++] = 0.95f;
            triangleVerts[tp++] = 0.16f;
        }
        trails.setTrail(4, triangleVerts, 4);

        // ===== 4. 纪元分类与事件 =====
        Epoch epoch = classifier.classify(sim);
        if (currentEpoch == null || epoch.type() != currentEpoch.type()) {
            eventBus.publish(new EpochChangedEvent(currentEpoch, epoch));
            log.info(String.format("纪元变更: %s -> %s (温度 %.3f, 最近曜 %.2f)",
                    currentEpoch != null ? currentEpoch.type().getDisplayName() : "(初始)",
                    epoch.type().getDisplayName(), epoch.temperature(), epoch.nearestDist()));
            if (epoch.type() == com.mog.astro.EpochType.LOST) {
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

    // ===== 时间控制（场景 handleInput 调用）=====

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

    public Epoch getCurrentEpoch() {
        return currentEpoch;
    }

    public GravitySimulation getSim() {
        return sim;
    }

    // ===== 乐章状态（HUD / 结算流程读取）=====

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
