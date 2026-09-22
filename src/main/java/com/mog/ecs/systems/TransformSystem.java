package com.mog.ecs.systems;

import com.mog.ecs.GameSystem;
import com.mog.ecs.Transforms;
import com.mog.ecs.World;
import com.mog.ecs.components.ParentComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.ecs.components.WorldMatrixComponent;
import org.joml.Matrix4f;

/**
 * 变换系统：每帧计算所有实体的世界矩阵（场景图层级解析）。
 *   世界矩阵 = 父世界矩阵 × 本地 TRS；无父级时 = 本地 TRS。
 * 必须注册在修改 Transform 的系统（Spin/Orbit）之后、渲染之前。
 * 递归 + upToDate 记忆化：父链只算一次，兄弟子实体直接复用。
 */
public class TransformSystem implements GameSystem {

    /** 递归深度上限：防组件误接成环导致栈溢出 */
    private static final int MAX_DEPTH = 64;

    /** 本地矩阵暂存（递归内复用，避免与目标矩阵发生别名自乘） */
    private final Matrix4f localScratch = new Matrix4f();

    @Override
    public void update(World world, float deltaTime) {
        // 1. 确保每个 Transform 实体都有世界矩阵组件，并置脏
        for (int e : world.view(TransformComponent.class)) {
            WorldMatrixComponent wm = world.getComponent(e, WorldMatrixComponent.class);
            if (wm == null) {
                wm = world.addComponent(e, new WorldMatrixComponent());
            }
            wm.markDirty();
        }
        // 2. 递归求解（记忆化避免重复计算父链）
        for (int e : world.view(TransformComponent.class)) {
            computeWorldMatrix(world, e, 0);
        }
    }

    private void computeWorldMatrix(World world, int entity, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("场景图深度超过 " + MAX_DEPTH
                    + "，疑似父子关系成环，实体 #" + entity + " (" + world.getName(entity) + ")");
        }
        WorldMatrixComponent wm = world.getComponent(entity, WorldMatrixComponent.class);
        if (wm == null || wm.isUpToDate()) {
            return;
        }
        ParentComponent parent = world.getComponent(entity, ParentComponent.class);
        if (parent != null) {
            int pid = parent.getParentId();
            // 先递归算父级——递归内部会覆写 localScratch，
            // 所以本层的本地矩阵必须等递归返回后再构建（顺序不能反！）
            computeWorldMatrix(world, pid, depth + 1);
            WorldMatrixComponent pwm = world.getComponent(pid, WorldMatrixComponent.class);
            TransformComponent t = world.getComponent(entity, TransformComponent.class);
            Transforms.buildLocalMatrix(t, localScratch);
            if (pwm != null) {
                // 世界 = 父世界 × 本地（严禁 wm.set(父).mul(wm) 的别名自乘）
                wm.getMatrix().set(pwm.getMatrix()).mul(localScratch);
                wm.markUpToDate();
                return;
            }
        }
        // 无父级（或父级无世界矩阵）：世界 = 本地
        Transforms.buildLocalMatrix(world.getComponent(entity, TransformComponent.class), wm.getMatrix());
        wm.markUpToDate();
    }
}
