package com.mog.ecs.components;

import com.mog.ecs.Component;

/**
 * 粒子发射器组件（纯数据）：挂在实体上，从其 Transform 位置持续发射粒子。
 * 颜色自动取同实体的 PointLightComponent（灯喷同色火花），无灯则白色。
 */
public class EmitterComponent implements Component {

    private final float rate;         // 每秒发射数
    private final float lifetime;     // 粒子寿命（秒）
    private final float speed;        // 初速度（单位/秒）
    private final float spread;       // 方向随机锥角程度（0~1）

    public EmitterComponent(float rate, float lifetime, float speed, float spread) {
        this.rate = rate;
        this.lifetime = lifetime;
        this.speed = speed;
        this.spread = spread;
    }

    public float getRate() {
        return rate;
    }

    public float getLifetime() {
        return lifetime;
    }

    public float getSpeed() {
        return speed;
    }

    public float getSpread() {
        return spread;
    }
}
