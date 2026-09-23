package com.mog.astro;

/**
 * 乐章终局类型（GDD 结局矩阵的物理触发端映射，显示名按原创 IP 术语）。
 *
 * 结局矩阵衔接：
 *   坠焚 / 失家 -> 毁灭结算（文明消亡 -> 传承点结算 -> 星海轮回新种子重开）
 *   恒星弹射    -> 乐章终结演出 + 逃亡判定（S3 演出层接入）
 */
public enum EndingType {
    PLANET_SCORCHED("坠焚·毁灭结算"),
    PLANET_LOST("失家·冰封远航"),
    STAR_EJECTED("恒星弹射·乐章终结");

    private final String displayName;

    EndingType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
