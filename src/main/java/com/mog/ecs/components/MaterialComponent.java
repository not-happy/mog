package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.render.Material;

/**
 * 材质组件：拥有此组件的实体走光照管线。纯数据。
 */
public class MaterialComponent implements Component {

    private final Material material;

    public MaterialComponent(Material material) {
        this.material = material;
    }

    public Material getMaterial() {
        return material;
    }
}
