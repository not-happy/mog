package com.mog.core;

import com.mog.ecs.World;
import com.mog.game.CameraRig;
import com.mog.game.Light;
import com.mog.input.InputHandler;
import com.mog.render.Camera;
import com.mog.render.ParticleEngine;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 场景接口：一个可被 Game 装载/切换的完整游戏场景。
 * Game 通过 Tab 在地表与宇宙场景间切换；场景差异一律通过下面的能力方法
 * （wantsMouseCapture / usesCrosshairPicking / supportsSaveLoad / getHintLines /
 * drawsCrosshair）向 Game 声明——Game 里不允许出现 instanceof 具体场景类。
 */
public interface Scene {

    /** 构建世界：创建实体、挂载组件、注册系统、加载资源。 */
    void init();

    /** 固定步长驱动（由 Game 主循环以 60Hz 调用）。 */
    void update(float deltaTime);

    World getWorld();

    Camera getCamera();

    Light getLight();

    /** 本场景的相机操控装备（FPS 漫游 / 轨道环绕）；null 表示无相机控制。 */
    default CameraRig getCameraRig() {
        return null;
    }

    /** 每帧输入处理（场景专属热键：如宇宙场景的暂停/倍速）。 */
    default void handleInput(InputHandler input) {
    }

    /** 场景专属 HUD 行（Game 统一绘制）。 */
    default List<String> getHudLines() {
        return List.of();
    }

    /** 场景遍内的附加绘制（如轨道残影线），主遍之后、后处理之前。 */
    default void renderOverlay(Camera camera, Matrix4f projection) {
    }

    /** 粒子引擎（场景遍内渲染用）；无粒子的场景返回 null。 */
    default ParticleEngine getParticleEngine() {
        return null;
    }

    // ===== 能力声明（Game 据此装配输入/HUD/热键，取代 instanceof 判断）=====

    /** 进入本场景时是否锁定光标（FPS 漫游 true；拖拽操作的宇宙/RTS 场景 false）。 */
    default boolean wantsMouseCapture() {
        return true;
    }

    /** 是否启用准星拾取（左键从屏幕中心发射线选碰撞体）。 */
    default boolean usesCrosshairPicking() {
        return false;
    }

    /** 是否支持 F9/F10 存档读档。 */
    default boolean supportsSaveLoad() {
        return false;
    }

    /** 屏幕底部操作提示行（静态文案；动态状态走 getHudLines）。 */
    default List<String> getHintLines() {
        return List.of();
    }

    /** 是否在屏幕中心画准星"+"。 */
    default boolean drawsCrosshair() {
        return false;
    }

    /** 释放场景持有的资源（GL 资源必须在上下文销毁前调用）。 */
    void cleanup();
}
