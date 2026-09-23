package com.mog.ecs;

import com.mog.ecs.components.TransformComponent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    /**
     * 把仿射矩阵分解为 T·R·S（要求无剪切/镜像，骨骼与节点变换满足）。
     * 用途：动画通道缺轨时回退到节点初始姿势的对应分量。
     *
     * 注意 JOML 命名是 m&lt;col&gt;&lt;row&gt;：数学矩阵 M(row,col) = m&lt;col&gt;&lt;row&gt;()。
     */
    public static void decompose(Matrix4f m, Vector3f pos, Quaternionf rot, Vector3f scale) {
        m.getTranslation(pos);

        // 缩放 = 各列向量长度（列 c = (m<c>0, m<c>1, m<c>2)）
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12());
        float sz = (float) Math.sqrt(m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22());
        scale.set(sx, sy, sz);

        // 去掉缩放得到纯旋转矩阵 N（数学下标 N<row><col>）
        float ix = sx > 1e-8f ? 1f / sx : 0f;
        float iy = sy > 1e-8f ? 1f / sy : 0f;
        float iz = sz > 1e-8f ? 1f / sz : 0f;
        float n00 = m.m00() * ix, n01 = m.m10() * iy, n02 = m.m20() * iz;
        float n10 = m.m01() * ix, n11 = m.m11() * iy, n12 = m.m21() * iz;
        float n20 = m.m02() * ix, n21 = m.m12() * iy, n22 = m.m22() * iz;

        // 旋转矩阵 -> 四元数（Shepperd 法，按最大分量分支保数值稳定）
        float tr = n00 + n11 + n22;
        float s2, w, x, y, z;
        if (tr > 0) {
            s2 = (float) Math.sqrt(tr + 1.0f) * 2f;
            w = 0.25f * s2;
            x = (n21 - n12) / s2;
            y = (n02 - n20) / s2;
            z = (n10 - n01) / s2;
        } else if (n00 > n11 && n00 > n22) {
            s2 = (float) Math.sqrt(1.0f + n00 - n11 - n22) * 2f;
            w = (n21 - n12) / s2;
            x = 0.25f * s2;
            y = (n01 + n10) / s2;
            z = (n02 + n20) / s2;
        } else if (n11 > n22) {
            s2 = (float) Math.sqrt(1.0f + n11 - n00 - n22) * 2f;
            w = (n02 - n20) / s2;
            x = (n01 + n10) / s2;
            y = 0.25f * s2;
            z = (n12 + n21) / s2;
        } else {
            s2 = (float) Math.sqrt(1.0f + n22 - n00 - n11) * 2f;
            w = (n10 - n01) / s2;
            x = (n02 + n20) / s2;
            y = (n12 + n21) / s2;
            z = 0.25f * s2;
        }
        rot.set(x, y, z, w);
    }
}
