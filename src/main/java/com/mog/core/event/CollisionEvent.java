package com.mog.core.event;

/** 两个碰撞体开始重叠事件（由 CollisionSystem 在检测到新重叠时发布）。 */
public record CollisionEvent(int entityA, int entityB, String nameA, String nameB) {
}
