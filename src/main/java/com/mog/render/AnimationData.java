package com.mog.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 动画数据（纯数据）：若干通道，每通道绑定一个节点并持有 位置/旋转/缩放 三条关键帧轨。
 */
public class AnimationData {

    private final String name;
    private final float duration;       // 单位：tick
    private final float ticksPerSecond; // glTF 通常 1.0（时间即秒）；缺失时按 25 处理
    private final Channel[] channels;
    /** 节点索引 -> 通道下标（-1 = 无动画通道），采样时免查表 */
    private final int[] channelOfNode;

    public AnimationData(String name, float duration, float ticksPerSecond,
                         Channel[] channels, int nodeCount) {
        this.name = name;
        this.duration = duration;
        this.ticksPerSecond = ticksPerSecond;
        this.channels = channels;
        this.channelOfNode = new int[nodeCount];
        java.util.Arrays.fill(channelOfNode, -1);
        for (int i = 0; i < channels.length; i++) {
            int node = channels[i].getNodeIndex();
            if (node >= 0 && node < nodeCount) {
                channelOfNode[node] = i;
            }
        }
    }

    public String getName() {
        return name;
    }

    public float getDuration() {
        return duration;
    }

    public float getTicksPerSecond() {
        return ticksPerSecond;
    }

    public Channel[] getChannels() {
        return channels;
    }

    /** 取某节点的动画通道；无则 null。 */
    public Channel getChannelForNode(int nodeIndex) {
        int ci = (nodeIndex >= 0 && nodeIndex < channelOfNode.length) ? channelOfNode[nodeIndex] : -1;
        return ci >= 0 ? channels[ci] : null;
    }

    /** 单条通道：一个节点的 TRS 关键帧轨。 */
    public static class Channel {
        private final int nodeIndex;
        private final float[] posTimes;
        private final Vector3f[] posValues;
        private final float[] rotTimes;
        private final Quaternionf[] rotValues;
        private final float[] scaleTimes;
        private final Vector3f[] scaleValues;

        public Channel(int nodeIndex,
                       float[] posTimes, Vector3f[] posValues,
                       float[] rotTimes, Quaternionf[] rotValues,
                       float[] scaleTimes, Vector3f[] scaleValues) {
            this.nodeIndex = nodeIndex;
            this.posTimes = posTimes;
            this.posValues = posValues;
            this.rotTimes = rotTimes;
            this.rotValues = rotValues;
            this.scaleTimes = scaleTimes;
            this.scaleValues = scaleValues;
        }

        public int getNodeIndex() {
            return nodeIndex;
        }

        public float[] getPosTimes() { return posTimes; }
        public Vector3f[] getPosValues() { return posValues; }
        public float[] getRotTimes() { return rotTimes; }
        public Quaternionf[] getRotValues() { return rotValues; }
        public float[] getScaleTimes() { return scaleTimes; }
        public Vector3f[] getScaleValues() { return scaleValues; }
    }
}
