package com.mog.ecs.systems;

import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.MeshComponent;
import com.mog.ecs.components.ResourceNodeComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.game.ResourceNode;
import com.mog.game.SurfaceSession;
import com.mog.render.Mesh;

/**
 * 地表视图同步系统（C3）：会话 -> ECS 的薄壳（仿 CosmosViewSystem 先例），
 * 只注册进地表 World，每帧从 SurfaceSession 真源抄写视图状态。逻辑零参与——
 * 状态机/储量结算全部在会话（实体类不放逻辑，系统只做视图搬运）。
 *
 * 批 1：资源节点——储量比例 -> 缩放（0.55..1.20），枯竭定格 0.4 灰球留原地当墓碑；
 * 枯竭迁移沿换灰 Mesh（addComponent 为 put 覆盖语义，World.java 已核实；
 * 迁移沿低频分配豁免零分配红线，用引用比较触发、无每帧分配）。
 * 批 2：采集者（位置直抄会话缓存的 x/y/z，满载/空载换色 Mesh）。
 *
 * view() 纪律：两个不同签名顺序遍历，不嵌套、不跨帧持有结果列表。
 * agentById/nodeById 判 null 防御（会话复位竞态：continue 跳过，等场景重建自愈）。
 */
public class SurfaceViewSystem implements GameSystem {

    /** 节点基础/满储缩放：scale = MIN + RANGE × (ore/maxOre)（球直径 1，缩放即直径米数） */
    private static final float NODE_SCALE_MIN = 0.55f;
    private static final float NODE_SCALE_RANGE = 0.65f;
    /** 枯竭定格缩放（灰球墓碑） */
    private static final float NODE_SCALE_DEPLETED = 0.4f;
    /** 半埋感：球心抬升 = scale × 0.55（略高于半球 0.5，随储量收缩下沉） */
    private static final float NODE_Y_FACTOR = 0.55f;

    private final SurfaceSession session;
    private final Mesh nodeMeshRich;
    private final Mesh nodeMeshDepleted;

    public SurfaceViewSystem(SurfaceSession session, Mesh nodeMeshRich, Mesh nodeMeshDepleted) {
        this.session = session;
        this.nodeMeshRich = nodeMeshRich;
        this.nodeMeshDepleted = nodeMeshDepleted;
    }

    @Override
    public void update(World world, float deltaTime) {
        for (int e : world.view(ResourceNodeComponent.class, TransformComponent.class)) {
            ResourceNodeComponent nc = world.getComponent(e, ResourceNodeComponent.class);
            ResourceNode n = session.nodeById(nc.getNodeId());
            if (n == null) {
                continue; // 防御：会话已复位而场景未重建，跳过等自愈
            }
            TransformComponent t = world.getComponent(e, TransformComponent.class);
            float scale = n.isDepleted()
                    ? NODE_SCALE_DEPLETED
                    : NODE_SCALE_MIN + NODE_SCALE_RANGE * (n.getOre() / (float) n.getMaxOre());
            t.getScale().set(scale, scale, scale);
            t.getPosition().set(n.getWorldX(), n.getGroundY() + scale * NODE_Y_FACTOR, n.getWorldZ());

            // 枯竭迁移沿：换灰 Mesh（put 覆盖；引用比较即迁移沿检测，稳态零分配）
            Mesh want = n.isDepleted() ? nodeMeshDepleted : nodeMeshRich;
            MeshComponent mc = world.getComponent(e, MeshComponent.class);
            if (mc != null && mc.getMesh() != want) {
                world.addComponent(e, new MeshComponent(want));
            }
        }
    }
}
