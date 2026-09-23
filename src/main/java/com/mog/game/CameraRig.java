package com.mog.game;

import com.mog.input.InputHandler;

/**
 * 相机装备接口：不同场景用不同的相机操控方式
 * （地表 = FPS 漫游 CameraController；宇宙 = 轨道环绕 OrbitCameraRig）。
 */
public interface CameraRig {

    /**
     * @param input      输入状态
     * @param deltaTime  帧耗时（秒）
     * @param mouseLook  鼠标视角是否激活（光标捕获中）；轨道相机忽略此参数
     */
    void update(InputHandler input, float deltaTime, boolean mouseLook);
}
