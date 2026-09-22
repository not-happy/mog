package com.mog.ecs.components;

import com.mog.ecs.Component;
import org.joml.Matrix4f;

/**
 * 世界矩阵组件：TransformSystem 每帧计算并缓存的最终模型矩阵（含父级层级）。
 * Renderer 直接读取，避免渲染循环里重复递归。
 * upToDate 标记用于同帧内的记忆化（父算一次，多子共享）。
 */
public class WorldMatrixComponent implements Component {

    private final Matrix4f matrix = new Matrix4f();
    private boolean upToDate;

    public Matrix4f getMatrix() {
        return matrix;
    }

    public boolean isUpToDate() {
        return upToDate;
    }

    public void markUpToDate() {
        upToDate = true;
    }

    public void markDirty() {
        upToDate = false;
    }
}
