package com.mog.ecs.components;

import com.mog.ecs.Component;
import org.joml.Matrix4f;

/**
 * 骨骼矩阵缓存组件：AnimationSystem 每帧写入，Renderer 直接上传 uniform。
 * 有它的实体走蒙皮管线。
 */
public class BoneMatricesComponent implements Component {

    private final Matrix4f[] matrices;

    public BoneMatricesComponent(int boneCount) {
        matrices = new Matrix4f[boneCount];
        for (int i = 0; i < boneCount; i++) {
            matrices[i] = new Matrix4f();
        }
    }

    public Matrix4f[] getMatrices() {
        return matrices;
    }

    public int getBoneCount() {
        return matrices.length;
    }
}
