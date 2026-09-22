package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.render.PbrMaterial;

/**
 * PBR 材质组件：拥有此组件的实体走 PBR 管线（优先于经典 MaterialComponent）。纯数据。
 */
public class PbrMaterialComponent implements Component {

    private final PbrMaterial material;

    public PbrMaterialComponent(PbrMaterial material) {
        this.material = material;
    }

    public PbrMaterial getPbrMaterial() {
        return material;
    }
}
