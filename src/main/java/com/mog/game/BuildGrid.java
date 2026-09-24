package com.mog.game;

/**
 * 建造网格（C2 骨架：坐标系约定 + 静态换算工具，无状态）。
 *
 * 网格覆盖 XZ 平面 [-HALF_EXTENT, +HALF_EXTENT]²，共 CELLS×CELLS 格、每格 CELL_SIZE 米。
 * C3 建造核心将在此之上叠加占位 bitmap 与落位校验——本类只提供纯函数，
 * 不持有任何游戏状态（实体类不放逻辑的项目规则同样适用于数据类）。
 */
public final class BuildGrid {

    /** 每轴格数 */
    public static final int CELLS = 32;
    /** 单格边长（米） */
    public static final float CELL_SIZE = 2.0f;
    /** 网格半宽（米）：全宽 64m，与阴影正交 extent=48 覆盖范围匹配 */
    public static final float HALF_EXTENT = CELLS * CELL_SIZE / 2f;

    private BuildGrid() {
    }

    /** 世界坐标是否落在网格范围内。 */
    public static boolean inBounds(float x, float z) {
        return x >= -HALF_EXTENT && x <= HALF_EXTENT && z >= -HALF_EXTENT && z <= HALF_EXTENT;
    }

    /** 世界坐标 -> 格索引（越界钳制到 [0, CELLS-1]）。 */
    public static int worldToCell(float w) {
        int c = (int) Math.floor((w + HALF_EXTENT) / CELL_SIZE);
        return Math.max(0, Math.min(CELLS - 1, c));
    }

    /** 格索引 -> 格中心世界坐标。 */
    public static float cellToWorld(int cell) {
        return -HALF_EXTENT + (cell + 0.5f) * CELL_SIZE;
    }

    /** 世界坐标吸附到最近格中心（C3 建造落位用）。 */
    public static float snap(float w) {
        return cellToWorld(worldToCell(w));
    }
}
