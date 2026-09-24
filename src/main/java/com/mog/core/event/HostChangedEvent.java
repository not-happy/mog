package com.mog.core.event;

/**
 * 易天事件：行星宿主星易主（HostTracker 滞回判定通过后由 CosmosSession 发布）。
 * 叙事口径：文明天空换了一颗太阳——历法仍用出生之年的旧尺子丈量（GameCalendar）。
 */
public record HostChangedEvent(int oldHost, int newHost, double simTime) {
}
