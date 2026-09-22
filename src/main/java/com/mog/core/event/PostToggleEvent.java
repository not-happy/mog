package com.mog.core.event;

/** 后处理开关切换事件（演示事件驱动：音频/UI 等可订阅响应，无需认识 Game）。 */
public record PostToggleEvent(boolean enabled) {
}
