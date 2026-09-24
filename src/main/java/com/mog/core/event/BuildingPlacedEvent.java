package com.mog.core.event;

/**
 * 建筑放置事件（C3）：SurfaceSession 发布（场景零事件红线），Game 级订阅
 * （音效/日志，场景不得订阅）。
 * typeName 用 String（BuildingType.name()）——core.event 不反向依赖 game 包。
 * entityId 为会话**逻辑占位 id**（跨 Tab 恒定，非 ECS 实体 id——实体随场景重建）。
 */
public record BuildingPlacedEvent(int entityId, String typeName,
                                  int cellX, int cellZ, int footW, int footD) {
}
