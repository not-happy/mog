package com.mog.game;

/**
 * 资源节点（C3）：种子生成的有限矿脉。纯数据，行为逻辑在
 * {@link SurfaceSession}（采集扣减/枯竭迁移）与 {@link ResourceNodeGenerator}（生成）。
 *
 * 节点占 1×1 建造格（防建筑压盖、天然寻路障碍），不可拆除。
 * groundY 为生成时缓存的贴地高度（TerrainBuilder.heightAt），视图直抄不再算。
 * 字段包级私有：只有同包的会话/生成器可写；跨包（ecs.systems 视图）走公开 getter。
 */
public final class ResourceNode {

    int id;
    int cellX;
    int cellZ;
    float worldX;
    float worldZ;
    float groundY;
    int maxOre;
    int ore;
    boolean depleted;

    ResourceNode(int id, int cellX, int cellZ, float worldX, float worldZ,
                 float groundY, int ore) {
        this.id = id;
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.worldX = worldX;
        this.worldZ = worldZ;
        this.groundY = groundY;
        this.maxOre = ore;
        this.ore = ore;
    }

    /** 逻辑占位 id（与建筑共用会话 id 空间，跨 Tab 恒定）。 */
    public int getId() {
        return id;
    }

    public int getCellX() {
        return cellX;
    }

    public int getCellZ() {
        return cellZ;
    }

    public float getWorldX() {
        return worldX;
    }

    public float getWorldZ() {
        return worldZ;
    }

    /** 生成时缓存的贴地高度（米）。 */
    public float getGroundY() {
        return groundY;
    }

    public int getMaxOre() {
        return maxOre;
    }

    public int getOre() {
        return ore;
    }

    public boolean isDepleted() {
        return depleted;
    }
}
