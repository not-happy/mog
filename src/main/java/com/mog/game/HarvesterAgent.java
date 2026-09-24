package com.mog.game;

/**
 * 采集者智能体（C3 批 2）：可放置搬运单位。纯数据，状态机/移动积分全部在
 * {@link SurfaceSession}（实体类不放逻辑）。
 *
 * 不占建造格、不扣费：自由坐标放置（左键拾取点），y 由会话按 heightAt 贴地缓存，
 * 视图系统直抄。targetNode/homeStation 存**逻辑 id**（-1=未绑定），跨 Tab 恒定。
 * 字段包级私有：只有会话可写；跨包（ecs.systems 视图）走公开 getter。
 */
public final class HarvesterAgent {

    int id;
    HarvesterState state = HarvesterState.IDLE;
    float x;
    float z;
    /** 贴地高度缓存（heightAt），视图直抄 */
    float y;
    /** 目标资源节点逻辑 id（-1=无） */
    int targetNode = -1;
    /** 归属卸货站逻辑 id（最近 COLLECTOR，回退 COMMAND_HUB；-1=无） */
    int homeStation = -1;
    /** 当前携带矿石量（0=空载） */
    int carry;
    /** 采集/卸货工时计时器（模拟时间单位，受纪元衰减倍率） */
    float workTimer;
    /** IDLE 重试绑定倒计时（秒） */
    float idleRetry;
    // ===== 流场寻路游标（批 2）=====
    int cellX = -1;
    int cellZ = -1;
    int nextCellX = -1;
    int nextCellZ = -1;

    HarvesterAgent(int id, float x, float z, float y) {
        this.id = id;
        this.x = x;
        this.z = z;
        this.y = y;
    }

    /** 逻辑 id（会话 id 空间，跨 Tab 恒定）。 */
    public int getId() {
        return id;
    }

    public HarvesterState getState() {
        return state;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    public int getCarry() {
        return carry;
    }
}
