package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.game.PointLight;

/**
 * 点光源组件：实体即一盏灯。纯数据。
 * 技巧：构造 PointLight 时直接传入 TransformComponent 的 position 向量实例（共享引用），
 * OrbitSystem 移动 Transform 时灯光位置自动同步，无需第二个系统。
 */
public class PointLightComponent implements Component {

    private final PointLight light;

    public PointLightComponent(PointLight light) {
        this.light = light;
    }

    public PointLight getLight() {
        return light;
    }
}
