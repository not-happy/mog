package com.mog.render;

import com.mog.ecs.Transforms;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * 骨架数据（纯数据）：节点树 + 骨骼逆绑定矩阵 + 每节点初始 TRS 分解。
 *
 * 概念对照（Assimp/glTF）：
 *   - 节点(node)：变换层级树的关节（未必都是骨骼）
 *   - 骨骼(bone)：真正影响顶点的节点引用 + 逆绑定矩阵(offset matrix)
 *   - 蒙皮公式：boneMatrix[i] = nodeGlobal[骨骼i所在节点] × boneOffset[i]
 *
 * 初始 TRS 分解的用途：动画通道经常缺轨（如 glTF 骨骼动画多数只有关节旋转轨），
 * 缺失的轨道必须回退到节点初始姿势的对应分量，绝不能回退到零——否则关节塌到原点，
 * 模型被拉成面条（CesiumMan "通天面人" 事故的根因）。
 *
 * 节点索引按"先序遍历"分配：父节点索引恒小于子节点，求解全局变换可单遍顺序完成。
 */
public class Skeleton {

    private final int nodeCount;
    private final String[] nodeNames;
    private final int[] parentIndex;                 // -1 = 根
    private final Matrix4f[] nodeLocalTransforms;    // 初始姿势的本地变换
    private final Vector3f[] initPos;                // 初始 TRS 分解（缺轨回退用）
    private final Quaternionf[] initRot;
    private final Vector3f[] initScale;
    private final Map<String, Integer> nodeIndexByName = new HashMap<>();

    private final int boneCount;
    private final int[] boneNodeIndex;               // 骨骼 -> 节点索引
    private final Matrix4f[] boneOffsets;            // 骨骼 -> 逆绑定矩阵

    public Skeleton(int nodeCount, String[] nodeNames, int[] parentIndex,
                    Matrix4f[] nodeLocalTransforms,
                    int boneCount, int[] boneNodeIndex, Matrix4f[] boneOffsets) {
        this.nodeCount = nodeCount;
        this.nodeNames = nodeNames;
        this.parentIndex = parentIndex;
        this.nodeLocalTransforms = nodeLocalTransforms;
        this.boneCount = boneCount;
        this.boneNodeIndex = boneNodeIndex;
        this.boneOffsets = boneOffsets;
        this.initPos = new Vector3f[nodeCount];
        this.initRot = new Quaternionf[nodeCount];
        this.initScale = new Vector3f[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodeIndexByName.put(nodeNames[i], i);
            initPos[i] = new Vector3f();
            initRot[i] = new Quaternionf();
            initScale[i] = new Vector3f();
            Transforms.decompose(nodeLocalTransforms[i], initPos[i], initRot[i], initScale[i]);
        }
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getParentIndex(int node) {
        return parentIndex[node];
    }

    public Matrix4f getNodeLocalTransform(int node) {
        return nodeLocalTransforms[node];
    }

    public Vector3f getInitPos(int node) {
        return initPos[node];
    }

    public Quaternionf getInitRot(int node) {
        return initRot[node];
    }

    public Vector3f getInitScale(int node) {
        return initScale[node];
    }

    public int getNodeIndex(String name) {
        Integer i = nodeIndexByName.get(name);
        return i == null ? -1 : i;
    }

    public int getBoneCount() {
        return boneCount;
    }

    public int getBoneNodeIndex(int bone) {
        return boneNodeIndex[bone];
    }

    public Matrix4f getBoneOffset(int bone) {
        return boneOffsets[bone];
    }
}
