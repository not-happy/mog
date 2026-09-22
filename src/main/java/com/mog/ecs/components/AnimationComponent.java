package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.render.AnimationData;
import com.mog.render.Skeleton;

/**
 * 动画组件：绑定一段动画与骨架，持有播放状态（时间/速度/循环）。
 * 由 AnimationSystem 每帧推进并采样出骨骼矩阵。
 */
public class AnimationComponent implements Component {

    private final AnimationData animation;
    private final Skeleton skeleton;
    private final float speed;
    private final boolean loop;
    private float time;

    public AnimationComponent(AnimationData animation, Skeleton skeleton, float speed, boolean loop) {
        this.animation = animation;
        this.skeleton = skeleton;
        this.speed = speed;
        this.loop = loop;
    }

    public AnimationData getAnimation() {
        return animation;
    }

    public Skeleton getSkeleton() {
        return skeleton;
    }

    public float getSpeed() {
        return speed;
    }

    public boolean isLoop() {
        return loop;
    }

    public float getTime() {
        return time;
    }

    public void setTime(float time) {
        this.time = time;
    }
}
