package com.mog.core.event;

/**
 * 资源节点枯竭事件（C3 批 1）：SurfaceSession 发布（场景零事件红线），
 * Game 级订阅（日志 + 警示音效）。节点 id 为会话逻辑 id。
 */
public record ResourceDepletedEvent(int nodeId, int cellX, int cellZ) {
}
