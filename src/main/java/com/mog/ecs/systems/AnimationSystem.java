package com.mog.ecs.systems;

import com.mog.ecs.GameSystem;
import com.mog.ecs.World;
import com.mog.ecs.components.AnimationComponent;
import com.mog.ecs.components.BoneMatricesComponent;
import com.mog.render.Animator;
import org.joml.Matrix4f;

/**
 * 动画系统：推进播放时间（循环取模），采样骨骼矩阵写入 BoneMatricesComponent。
 * 节点全局变换的暂存数组按最大骨架规模复用，避免每帧分配。
 */
public class AnimationSystem implements GameSystem {

    private Matrix4f[] globalScratch = new Matrix4f[0];
    private final Matrix4f localScratch = new Matrix4f();

    @Override
    public void update(World world, float deltaTime) {
        for (int e : world.view(AnimationComponent.class)) {
            AnimationComponent anim = world.getComponent(e, AnimationComponent.class);

            // 推进时间（秒），循环动画取模
            float t = anim.getTime() + deltaTime * anim.getSpeed();
            float durationSec = anim.getAnimation().getDuration()
                    / Math.max(anim.getAnimation().getTicksPerSecond(), 1e-6f);
            if (anim.isLoop() && durationSec > 0) {
                t %= durationSec;
            }
            anim.setTime(t);

            // 确保骨骼矩阵组件存在（首帧自动挂载）
            BoneMatricesComponent bones = world.getComponent(e, BoneMatricesComponent.class);
            if (bones == null) {
                bones = world.addComponent(e,
                        new BoneMatricesComponent(anim.getSkeleton().getBoneCount()));
            }

            // 暂存数组按需扩容（复用，不每帧分配）
            int nodes = anim.getSkeleton().getNodeCount();
            if (globalScratch.length < nodes) {
                globalScratch = new Matrix4f[nodes];
                for (int i = 0; i < nodes; i++) {
                    globalScratch[i] = new Matrix4f();
                }
            }

            Animator.sample(anim.getAnimation(), t, anim.getSkeleton(),
                    bones.getMatrices(), globalScratch, localScratch);
        }
    }
}
