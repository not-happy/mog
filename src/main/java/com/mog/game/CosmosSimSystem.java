package com.mog.game;

import com.mog.astro.Epoch;
import com.mog.astro.EpochClassifier;
import com.mog.astro.GravitySimulation;
import com.mog.core.event.EpochChangedEvent;
import com.mog.core.event.EventBus;
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
 * 模拟与渲染解耦：本系统是 GameSystem（进 World 调度），
 * 残影顶点数组每帧重建后交给 TrailRenderer（渲染由场景在 overlay 阶段触发）。
 */
public class CosmosSimSystem implements GameSystem {

    private static final Logger log = LoggerFactory.getLogger(CosmosSimSystem.class);

    /** 积分固定步长（模拟时间单位） */
    public static final double SIM_DT = 0.002;
    /** 单帧最大子步数（防卡顿后的死亡螺旋） */
    private static final int MAX_STEPS_PER_FRAME = 800;
    /** 每条轨迹的环形缓冲容量 */
    public static final int TRAIL_CAP = 2400;
    /** 每 N 个积分步记录一个轨迹点 */
    private static final int RECORD_EVERY = 6;

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

    // 轨迹环形缓冲（SoA）
    private final float[][] ringX = new float[4][TRAIL_CAP];
    private final float[][] ringY = new float[4][TRAIL_CAP];
    private final float[][] ringZ = new float[4][TRAIL_CAP];
    private final int[] trailCount = new int[4];
    private final int[] trailHead = new int[4];
    private final float[][] vertexScratch = new float[4][TRAIL_CAP * TrailRenderer.FLOATS_PER_VERTEX];

    private double stepAccum;
    private int stepCounter;
    private Epoch currentEpoch;

    /** 模拟速度（模拟时间单位/真实秒）与暂停状态（由场景 handleInput 控制） */
    private double speed = 5.0;
    private boolean paused;

    public CosmosSimSystem(GravitySimulation sim, int[] starEntities, int planetEntity,
                           TrailRenderer trails, EventBus eventBus) {
        this.sim = sim;
        this.starEntities = starEntities;
        this.planetEntity = planetEntity;
        this.trails = trails;
        this.eventBus = eventBus;
    }

    @Override
    public void update(World world, float deltaTime) {
        // ===== 1. 固定子步长推进积分 =====
        if (!paused) {
            stepAccum += speed * deltaTime;
            int steps = (int) (stepAccum / SIM_DT);
            if (steps > MAX_STEPS_PER_FRAME) {
                steps = MAX_STEPS_PER_FRAME;
                stepAccum = 0;
            } else {
                stepAccum -= steps * SIM_DT;
            }
            for (int s = 0; s < steps; s++) {
                sim.step(SIM_DT);
                if (++stepCounter % RECORD_EVERY == 0) {
                    recordTrailPoint();
                }
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

        // ===== 4. 纪元分类与事件 =====
        Epoch epoch = classifier.classify(sim);
        if (currentEpoch == null || epoch.type() != currentEpoch.type()) {
            eventBus.publish(new EpochChangedEvent(currentEpoch, epoch));
            log.info(String.format("纪元变更: %s -> %s (温度 %.3f, 最近曜 %.2f)",
                    currentEpoch != null ? currentEpoch.type().getDisplayName() : "(初始)",
                    epoch.type().getDisplayName(), epoch.temperature(), epoch.nearestDist()));
            if (epoch.type() == com.mog.astro.EpochType.LOST) {
                log.warn("母星已被弹射出三体系统，沿切线直线漂流——正式游戏中此事件触发"
                        + "『冰封远航』毁灭结算（文明冻结 -> 传承点结算 -> 新种子重开）");
            }
        }
        currentEpoch = epoch;
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
        speed = Math.max(0.5, Math.min(60.0, speed * factor));
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
}
