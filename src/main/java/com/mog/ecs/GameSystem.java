package com.mog.ecs;

/**
 * 系统接口：每帧由 World 调度，扫描拥有目标组件组合的实体并执行逻辑。
 * 命名为 GameSystem 以避免与 java.lang.System 冲突。
 */
public interface GameSystem {

    /**
     * @param world     实体组件仓库
     * @param deltaTime 帧耗时（秒）
     */
    void update(World world, float deltaTime);
}
