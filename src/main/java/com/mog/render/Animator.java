package com.mog.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 动画采样器：给定时间，求所有骨骼的蒙皮矩阵。
 *
 * 流程（每帧一次，与实体数无关）：
 *   1. 每个节点：有动画通道则按关键帧插值出本地 TRS，否则用初始姿势
 *   2. 按索引序（父先于子，先序遍历保证）累积全局变换
 *   3. boneMatrix[b] = nodeGlobal[骨骼节点] × boneOffset[b]
 *
 * 插值：位置/缩放线性，旋转球面(slerp)。教学版逐帧线性扫描关键帧；
 * 关键帧很多时可换二分（Assimp 保证关键帧按时间升序）。
 */
public final class Animator {

    private Animator() {
    }

    /**
     * @param anim        动画
     * @param timeSec     动画时间（秒，调用方负责循环取模）
     * @param skel        骨架
     * @param boneOut     输出：骨骼蒙皮矩阵（长度 = skel.getBoneCount()）
     * @param globalNodes 暂存：节点全局变换（长度 >= skel.getNodeCount()，调用方复用避免分配）
     * @param localScratch 暂存矩阵
     */
    public static void sample(AnimationData anim, float timeSec, Skeleton skel,
                              Matrix4f[] boneOut, Matrix4f[] globalNodes, Matrix4f localScratch) {
        float tps = anim.getTicksPerSecond() > 0 ? anim.getTicksPerSecond() : 25f;
        float tick = timeSec * tps;

        int n = skel.getNodeCount();
        Vector3f pos = new Vector3f();
        Quaternionf rot = new Quaternionf();
        Vector3f scl = new Vector3f();

        for (int i = 0; i < n; i++) {
            AnimationData.Channel ch = anim.getChannelForNode(i);
            if (ch != null) {
                // 缺轨回退到节点初始姿势的对应分量——绝不能回退到零，
                // 否则关节塌到原点，蒙皮顶点被拉成"通天面条"
                sampleVec3Track(ch.getPosTimes(), ch.getPosValues(), tick, pos, skel.getInitPos(i));
                sampleQuatTrack(ch.getRotTimes(), ch.getRotValues(), tick, rot, skel.getInitRot(i));
                sampleVec3Track(ch.getScaleTimes(), ch.getScaleValues(), tick, scl, skel.getInitScale(i));
                localScratch.identity().translate(pos).rotate(rot).scale(scl);
            } else {
                localScratch.set(skel.getNodeLocalTransform(i));
            }
            int parent = skel.getParentIndex(i);
            if (parent >= 0) {
                globalNodes[i].set(globalNodes[parent]).mul(localScratch);
            } else {
                globalNodes[i].set(localScratch);
            }
        }

        for (int b = 0; b < skel.getBoneCount(); b++) {
            boneOut[b].set(globalNodes[skel.getBoneNodeIndex(b)]).mul(skel.getBoneOffset(b));
        }
    }

    /** 向量轨采样：区间线性插值；空轨回退 fallback。 */
    private static void sampleVec3Track(float[] times, Vector3f[] values, float tick,
                                        Vector3f out, Vector3f fallback) {
        if (times == null || times.length == 0) {
            out.set(fallback);
            return;
        }
        if (tick <= times[0]) {
            out.set(values[0]);
            return;
        }
        if (tick >= times[times.length - 1]) {
            out.set(values[values.length - 1]);
            return;
        }
        for (int i = 0; i < times.length - 1; i++) {
            if (tick >= times[i] && tick < times[i + 1]) {
                float f = (tick - times[i]) / (times[i + 1] - times[i]);
                out.set(values[i]).lerp(values[i + 1], f);
                return;
            }
        }
        out.set(values[values.length - 1]);
    }

    /** 四元数轨采样：区间球面插值；空轨回退 fallback。 */
    private static void sampleQuatTrack(float[] times, Quaternionf[] values, float tick,
                                        Quaternionf out, Quaternionf fallback) {
        if (times == null || times.length == 0) {
            out.set(fallback);
            return;
        }
        if (tick <= times[0]) {
            out.set(values[0]);
            return;
        }
        if (tick >= times[times.length - 1]) {
            out.set(values[values.length - 1]);
            return;
        }
        for (int i = 0; i < times.length - 1; i++) {
            if (tick >= times[i] && tick < times[i + 1]) {
                float f = (tick - times[i]) / (times[i + 1] - times[i]);
                out.set(values[i]).slerp(values[i + 1], f);
                return;
            }
        }
        out.set(values[values.length - 1]);
    }
}
