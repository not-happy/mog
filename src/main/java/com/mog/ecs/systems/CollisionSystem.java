package com.mog.ecs.systems;

import com.mog.core.event.CollisionEvent;
import com.mog.core.event.EventBus;
import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.ColliderComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.ecs.components.WorldAabbComponent;
import com.mog.physics.Aabb;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 碰撞系统：刷新世界 AABB -> 两两窄相检测 -> 新重叠发布 CollisionEvent。
 *
 * 教学版实现：O(N²) 暴力两两检测，几十个碰撞体完全够用；
 * 实体上千时升级 broad-phase（均匀网格 / BVH / Sweep-and-Prune），窄相不变。
 * 旋转体暂用轴对齐近似（旋转 45° 的立方体 AABB 会偏大，属已知取舍）。
 */
public class CollisionSystem implements GameSystem {

    private final EventBus eventBus;
    /** 上一帧的重叠对（entityId 打包为 long），用于识别"新开始"的碰撞 */
    private final Set<Long> previousPairs = new HashSet<>();
    private final List<Integer> colliders = new ArrayList<>();

    public CollisionSystem(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void update(World world, float deltaTime) {
        // 1. 收集并刷新世界 AABB
        colliders.clear();
        colliders.addAll(world.view(TransformComponent.class, ColliderComponent.class));
        for (int e : colliders) {
            WorldAabbComponent wc = world.getComponent(e, WorldAabbComponent.class);
            if (wc == null) {
                wc = world.addComponent(e, new WorldAabbComponent());
            }
            refreshWorldAabb(world, e, wc.getAabb());
        }

        // 2. 两两窄相 + 新重叠发事件
        Set<Long> currentPairs = new HashSet<>();
        for (int i = 0; i < colliders.size(); i++) {
            int a = colliders.get(i);
            Aabb boxA = world.getComponent(a, WorldAabbComponent.class).getAabb();
            for (int j = i + 1; j < colliders.size(); j++) {
                int b = colliders.get(j);
                Aabb boxB = world.getComponent(b, WorldAabbComponent.class).getAabb();
                if (boxA.intersects(boxB)) {
                    long pair = pairKey(a, b);
                    currentPairs.add(pair);
                    if (!previousPairs.contains(pair)) {
                        eventBus.publish(new CollisionEvent(a, b,
                                world.getName(a), world.getName(b)));
                    }
                }
            }
        }
        previousPairs.clear();
        previousPairs.addAll(currentPairs);
    }

    /** 世界 AABB = 位置为中心，半长 = 本地半长 × 缩放（忽略旋转）。 */
    private static void refreshWorldAabb(World world, int entity, Aabb out) {
        TransformComponent t = world.getComponent(entity, TransformComponent.class);
        ColliderComponent c = world.getComponent(entity, ColliderComponent.class);
        Vector3f pos = t.getPosition();
        Vector3f scale = t.getScale();
        Vector3f half = c.getHalfExtent();
        out.getCenter().set(pos);
        out.getHalfExtent().set(half.x * scale.x, half.y * scale.y, half.z * scale.z);
    }

    /** 无序实体对 -> 唯一 long 键（小 id 放高 32 位）。 */
    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) hi << 32) | (lo & 0xFFFFFFFFL);
    }
}
