package com.mog.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 事件总线：发布-订阅模式，解耦"输入 -> 玩法 -> UI -> 音频"等子系统。
 *
 * 设计说明：
 *   - 按事件类型精确匹配（不含父类/接口派发，保持简单可预测）
 *   - CopyOnWriteArrayList：遍历派发期间允许订阅者增删（遍历快照安全）
 *   - 默认单线程（主循环）使用；跨线程发布需自行同步
 *   - 订阅者异常被捕获并记录，不允许一个处理器炸掉整条事件链
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <E> void subscribe(Class<E> eventType, Consumer<E> handler) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add((Consumer<Object>) handler);
    }

    public void publish(Object event) {
        List<Consumer<Object>> list = listeners.get(event.getClass());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Consumer<Object> handler : list) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                log.error("事件处理器异常 [{}]", event.getClass().getSimpleName(), e);
            }
        }
    }

    public void clear() {
        listeners.clear();
    }
}
