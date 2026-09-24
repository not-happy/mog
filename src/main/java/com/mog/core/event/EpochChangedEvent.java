package com.mog.core.event;

import com.mog.astro.Epoch;

/** 纪元切换事件（由 CosmosSession 在分类结果变化时发布）。 */
public record EpochChangedEvent(Epoch from, Epoch to) {
}
