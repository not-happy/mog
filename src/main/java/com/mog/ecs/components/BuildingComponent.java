package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.game.BuildingType;

/**
 * 建筑组件：标记实体为建造网格上的建筑。纯数据。
 *
 * cellX/cellZ 为足迹最小角（锚点格），足迹尺寸从 type 取，不冗余存储。
 * 拆除时凭此反查 OccupancyGrid 的清除范围。
 */
public class BuildingComponent implements Component {

    private BuildingType type;
    private int cellX;
    private int cellZ;

    public BuildingComponent(BuildingType type, int cellX, int cellZ) {
        this.type = type;
        this.cellX = cellX;
        this.cellZ = cellZ;
    }

    public BuildingType getType() {
        return type;
    }

    public void setType(BuildingType type) {
        this.type = type;
    }

    public int getCellX() {
        return cellX;
    }

    public void setCellX(int cellX) {
        this.cellX = cellX;
    }

    public int getCellZ() {
        return cellZ;
    }

    public void setCellZ(int cellZ) {
        this.cellZ = cellZ;
    }
}
