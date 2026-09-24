package com.mog.game;

import org.joml.Vector3f;

/**
 * 建筑类型静态表（C3 最小闭环，参照 {@link EpochPalette} 的代码内静态表先例）。
 *
 * 每项定义：显示名 / 足迹（格）/ 视觉尺寸（米）/ 颜色 / 成本（预留）。
 * 不变式：sizeX <= footW * CELL_SIZE、sizeZ <= footD * CELL_SIZE（视觉不越格）。
 * costCrystal 为 C4+ 资源系统预留字段，最小闭环不扣资源。
 */
public enum BuildingType {

    /** 指挥中枢：初始预置建筑，2×2 暖金方块 */
    COMMAND_HUB("指挥中枢", 2, 2, 3.6f, 2.2f, 3.6f, 0.72f, 0.60f, 0.40f, 0),
    /** 晶眠舱：1×1 蓝灰（人口保存，C4 实装功能） */
    CRYO_POD("晶眠舱", 1, 1, 1.6f, 1.0f, 1.6f, 0.42f, 0.55f, 0.62f, 0),
    /** 采集站：1×1 土黄（生产链起点，C3 后续实装） */
    COLLECTOR("采集站", 1, 1, 1.6f, 1.2f, 1.6f, 0.55f, 0.48f, 0.30f, 0),
    /** 天文台：1×1 青白（预测系统入口，C4 实装） */
    OBSERVATORY("天文台", 1, 1, 1.4f, 1.8f, 1.4f, 0.45f, 0.62f, 0.66f, 0),
    /** 列算阵：2×2 暗紫（研究奇观，GDD §4 人列计算机的术语重构） */
    COMPUTE_ARRAY("列算阵", 2, 2, 3.6f, 0.8f, 3.6f, 0.55f, 0.42f, 0.58f, 0);

    private final String displayName;
    private final int footW;
    private final int footD;
    private final float sizeX;
    private final float sizeY;
    private final float sizeZ;
    private final float r;
    private final float g;
    private final float b;
    private final int costCrystal;

    BuildingType(String displayName, int footW, int footD,
                 float sizeX, float sizeY, float sizeZ,
                 float r, float g, float b, int costCrystal) {
        this.displayName = displayName;
        this.footW = footW;
        this.footD = footD;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.r = r;
        this.g = g;
        this.b = b;
        this.costCrystal = costCrystal;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 足迹宽（X 轴格数） */
    public int getFootW() {
        return footW;
    }

    /** 足迹深（Z 轴格数） */
    public int getFootD() {
        return footD;
    }

    public float getSizeX() {
        return sizeX;
    }

    public float getSizeY() {
        return sizeY;
    }

    public float getSizeZ() {
        return sizeZ;
    }

    public float getR() {
        return r;
    }

    public float getG() {
        return g;
    }

    public float getB() {
        return b;
    }

    /** 建造成本（晶体）：C3 最小闭环预留为 0，不扣资源。 */
    public int getCostCrystal() {
        return costCrystal;
    }

    /** 颜色写入目标向量（零分配，供材质/高亮复用）。 */
    public Vector3f getColor(Vector3f dest) {
        return dest.set(r, g, b);
    }

    /**
     * 数字热键 -> 建筑类型（1 起，对应 GLFW_KEY_1..）。
     * @return 越界返回 null
     */
    public static BuildingType byHotkey(int key) {
        BuildingType[] all = values();
        if (key < 1 || key > all.length) {
            return null;
        }
        return all[key - 1];
    }
}
