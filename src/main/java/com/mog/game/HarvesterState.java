package com.mog.game;

/**
 * 采集者状态机（C3 批 2）：搬运循环 IDLE -> TO_NODE -> MINING -> TO_STATION
 * -> UNLOADING -> (续采 TO_NODE / 重指派)。纯枚举，迁移逻辑在 SurfaceSession。
 */
public enum HarvesterState {
    /** 待命：无归属节点（枯竭/中枢被拆），周期重试指派 */
    IDLE,
    /** 前往目标节点（去程流场） */
    TO_NODE,
    /** 抵达节点，采集计时中 */
    MINING,
    /** 满载返航（返程流场，根=归属卸货站） */
    TO_STATION,
    /** 抵达站点，卸货计时中 */
    UNLOADING
}
