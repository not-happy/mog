package com.mog.core.event;

/** 窗口帧缓冲尺寸变化事件（订阅者：Renderer 视口、PostProcessor 重建、UI 投影）。 */
public record WindowResizedEvent(int width, int height) {
}
