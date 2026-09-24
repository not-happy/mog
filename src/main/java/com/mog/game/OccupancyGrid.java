package com.mog.game;

import java.util.Arrays;

/**
 * 占位网格（C3）：记录建造格被哪个实体占用，支持矩形足迹放置/清除。
 *
 * 纯 Java 有状态逻辑，零 GL/ECS 依赖，可无头断言测试。
 * 与 {@link BuildGrid}（无状态坐标换算）分离：数据与逻辑分置。
 *
 * 约定：
 * - 光标格 = 足迹最小角，建筑覆盖 [cellX, cellX+footW-1] × [cellZ, cellZ+footD-1]
 * - 存储为 entityId+1（0 表示空——实体 id 可能为 0，必须偏移）
 * - canPlace 自带越界守卫（cx>=0 && cx+w<=CELLS）；调用方仍需先过
 *   {@link BuildGrid#inBounds} 再 worldToCell——worldToCell 会钳制越界坐标，
 *   逐格 inBounds 校验将恒真（钳制陷阱）。
 */
public class OccupancyGrid {

    private final int[] cells = new int[BuildGrid.CELLS * BuildGrid.CELLS];
    private int buildingCount;

    /** 光标格 (cx,cz) 起能否放下 footW×footD 足迹（越界或任一覆盖格被占 -> false）。 */
    public boolean canPlace(int cellX, int cellZ, int footW, int footD) {
        if (cellX < 0 || cellZ < 0
                || cellX + footW > BuildGrid.CELLS || cellZ + footD > BuildGrid.CELLS) {
            return false;
        }
        for (int z = cellZ; z < cellZ + footD; z++) {
            for (int x = cellX; x < cellX + footW; x++) {
                if (cells[z * BuildGrid.CELLS + x] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 占用足迹全部格子（调用前必须先 canPlace 通过）。 */
    public void place(int cellX, int cellZ, int footW, int footD, int entityId) {
        int stored = entityId + 1;
        for (int z = cellZ; z < cellZ + footD; z++) {
            for (int x = cellX; x < cellX + footW; x++) {
                cells[z * BuildGrid.CELLS + x] = stored;
            }
        }
        buildingCount++;
    }

    /** 清除足迹全部格子（拆除时凭 BuildingComponent 的锚点格与足迹调用）。 */
    public void clear(int cellX, int cellZ, int footW, int footD) {
        for (int z = cellZ; z < cellZ + footD; z++) {
            for (int x = cellX; x < cellX + footW; x++) {
                cells[z * BuildGrid.CELLS + x] = 0;
            }
        }
        buildingCount--;
    }

    /** 格上的实体 id；空格或越界返回 -1。 */
    public int entityAt(int cellX, int cellZ) {
        if (cellX < 0 || cellZ < 0 || cellX >= BuildGrid.CELLS || cellZ >= BuildGrid.CELLS) {
            return -1;
        }
        return cells[cellZ * BuildGrid.CELLS + cellX] - 1;
    }

    /** 当前建筑数（增减计数器维护，非扫描）。 */
    public int buildingCount() {
        return buildingCount;
    }

    /** 清空全部占位（场景重建/重开时调用）。 */
    public void reset() {
        Arrays.fill(cells, 0);
        buildingCount = 0;
    }
}
