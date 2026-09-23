package com.mog.astro;

/**
 * 纪元状态快照（纯数据）。
 *
 * @param type        纪元类型
 * @param temperature 归一化表面温度（Σ Lᵢ/dᵢ²，宜居带约 0.08~0.6）
 * @param nearestStar 最近恒星索引
 * @param nearestDist 最近恒星距离（模拟单位）
 */
public record Epoch(EpochType type, double temperature, int nearestStar, double nearestDist) {
}
