package com.mog.core;

import com.mog.ecs.World;
import com.mog.game.Light;
import com.mog.render.Camera;
import com.mog.render.ParticleEngine;

/**
 * 场景接口：一个可被 Game 装载/切换的完整游戏场景。
 * 为将来的 SceneManager（菜单场景/关卡场景切换）铺路。
 */
public interface Scene {

    /** 构建世界：创建实体、挂载组件、注册系统、加载资源。 */
    void init();

    /** 固定步长驱动（由 Game 主循环以 60Hz 调用）。 */
    void update(float deltaTime);

    World getWorld();

    Camera getCamera();

    Light getLight();

    /** 粒子引擎（场景遍内渲染用）；无粒子的场景返回 null。 */
    default ParticleEngine getParticleEngine() {
        return null;
    }

    /** 释放场景持有的资源（GL 资源必须在上下文销毁前调用）。 */
    void cleanup();
}
