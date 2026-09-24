package com.mog.game;

/**
 * 建筑记录（C3）：会话真源中的一栋建筑。纯数据。
 *
 * logicalId 是会话单调发放的逻辑占位 id——与 ECS 实体 id 解耦：
 * 实体随场景 Tab 切换销毁重建，逻辑 id 跨场景恒定（OccupancyGrid 存的也是它）。
 * cellX/cellZ 为足迹最小角（锚点格），与 BuildingComponent 语义一致。
 */
public record BuildingRecord(int logicalId, BuildingType type, int cellX, int cellZ) {
}
