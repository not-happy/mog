package com.mog.astro;

/**
 * 纪元类型（由三体几何实时判定，术语按原创 IP 映射表）。
 */
public enum EpochType {

    ORDER_YAO("序曜期"),     // 单星稳定照耀，宜居
    CHAOS_YAO("乱曜期"),     // 多星混沌，生产衰减
    SCORCH("烈曜"),          // 灼热灾难：过近或多星凌空
    FREEZE("寒曜"),          // 严寒灾难：三星皆远
    FLYBY("掠曜"),           // 飞星期：恒星高速逼近
    SYZYGY("三曜凌空"),      // 罕见奇观：三星同侧小角度汇聚
    LOST("失家深空");        // 终局事件：行星被弹射出星系，直线漂流（游戏中触发冻结毁灭结算）

    private final String displayName;

    EpochType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
