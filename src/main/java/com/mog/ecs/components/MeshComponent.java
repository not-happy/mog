package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.render.Mesh;

/**
 * 网格组件：引用渲染网格。纯数据（资源生命周期由创建方场景负责）。
 */
public class MeshComponent implements Component {

    private final Mesh mesh;

    public MeshComponent(Mesh mesh) {
        this.mesh = mesh;
    }

    public Mesh getMesh() {
        return mesh;
    }
}
