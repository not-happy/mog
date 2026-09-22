package com.mog.ecs.components;

import com.mog.ecs.Component;

/**
 * 父级组件：声明实体的父实体 ID，构成场景图层级。
 * 世界矩阵 = 父世界矩阵 × 本地 TRS（由 TransformSystem 计算）。
 * 要求层级无环（TransformSystem 有深度上限保护）。
 */
public class ParentComponent implements Component {

    private final int parentId;

    public ParentComponent(int parentId) {
        this.parentId = parentId;
    }

    public int getParentId() {
        return parentId;
    }
}
