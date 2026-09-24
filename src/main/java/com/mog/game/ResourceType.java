package com.mog.game;

/**
 * 资源类型（C3 生产链）：矿石 -> 金属 -> 电路（GDD §4）。
 * C3 只运转矿石链；金属/电路为配方表留位（N3 定稿后填表即生效）。
 * 纯数据枚举，行为逻辑在会话/工具类（全局规则）。
 */
public enum ResourceType {

    ORE("矿石"),
    METAL("金属"),
    CIRCUIT("电路");

    private final String displayName;

    ResourceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
