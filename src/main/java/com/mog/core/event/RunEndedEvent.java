package com.mog.core.event;

import com.mog.astro.EndingType;

/**
 * 乐章终结事件：首个终局事件成立时由 CosmosSimSystem 发布并冻结物理步进。
 *
 * 后续接入点（S3 演出层 / 结算流程）：
 *   PLANET_SCORCHED / PLANET_LOST -> 毁灭结算（传承点 -> 星海轮回新种子重开）
 *   STAR_EJECTED                  -> 终曲演出 + 逃亡判定（ejectedStar 为离场恒星索引）
 *
 * @param hostSwitches 本局累计易天次数（结算叙事素材：文明经历了几次换日）
 */
public record RunEndedEvent(EndingType ending, int ejectedStar,
                            double simTime, int hostSwitches) {
}
