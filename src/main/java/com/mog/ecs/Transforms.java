package com.mog.ecs;

import com.mog.ecs.components.TransformComponent;
import org.joml.Matrix4f;

/**
 * 变换计算工具（纯静态辅助类，逻辑不进组件）。
 */
public final class Transforms {

    private Transforms() {
    }

    /** 由 TransformComponent 数据构建本地模型矩阵（TRS：平移 -> 旋转 -> 缩放）。 */
    public static void buildLocalMatrix(TransformComponent t, Matrix4f dest) {
        dest.identity()
                .translate(t.getPosition())
                .rotateX(t.getRotation().x)
                .rotateY(t.getRotation().y)
                .rotateZ(t.getRotation().z)
                .scale(t.getScale());
    }
}
