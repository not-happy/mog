package com.mog.game;

import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.TransformComponent;
import com.mog.render.TrailRenderer;
import org.joml.Vector3d;

/**
 * 宇宙视图系统（薄壳）：把 {@link CosmosSession} 的模拟状态投影到渲染侧——
 * 同步天体实体 Transform（double -> float）+ 组装拖尾顶点交给 TrailRenderer。
 *
 * 只注册进 CosmosScene 的 World；模拟推进/事件发布全部在会话里（Game 固定步长 tick），
 * 本系统不碰模拟状态，场景销毁重建不影响会话。
 */
public class CosmosViewSystem implements GameSystem {

    private final CosmosSession session;
    private final int[] starEntities;
    private final int planetEntity;
    private final TrailRenderer trails;
    /** 拖尾顶点暂存（每帧重用，零分配；容量 = 满环 2400 点 × 7 float） */
    private final float[][] vertexScratch =
            new float[4][CosmosSession.TRAIL_CAP * TrailRenderer.FLOATS_PER_VERTEX];

    public CosmosViewSystem(CosmosSession session, int[] starEntities, int planetEntity,
                            TrailRenderer trails) {
        this.session = session;
        this.starEntities = starEntities;
        this.planetEntity = planetEntity;
        this.trails = trails;
    }

    @Override
    public void update(World world, float deltaTime) {
        // ===== 同步天体实体 Transform（double -> float 渲染精度足够）=====
        var sim = session.getSim();
        for (int i = 0; i < starEntities.length; i++) {
            Vector3d p = sim.getStarPos(i);
            TransformComponent t = world.getComponent(starEntities[i], TransformComponent.class);
            t.getPosition().set((float) p.x, (float) p.y, (float) p.z);
        }
        Vector3d pp = sim.getPlanetPos();
        world.getComponent(planetEntity, TransformComponent.class)
                .getPosition().set((float) pp.x, (float) pp.y, (float) pp.z);

        // ===== 拖尾顶点组装（旧 -> 新，alpha 渐隐在会话里算好）=====
        for (int b = 0; b < 4; b++) {
            int n = session.getTrailCount(b);
            float[] verts = vertexScratch[b];
            session.fillTrailVertices(b, verts);
            trails.setTrail(b, verts, n);
        }
    }
}
