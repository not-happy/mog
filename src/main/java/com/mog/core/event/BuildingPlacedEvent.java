package com.mog.core.event;

/**
 * 建筑放置事件（C3）：地表场景发布，Game 级订阅（音效/日志，场景不得订阅）。
 * typeName 用 String（BuildingType.name()）——core.event 不反向依赖 game 包。
 */
public record BuildingPlacedEvent(int entityId, String typeName,
                                  int cellX, int cellZ, int footW, int footD) {
}
