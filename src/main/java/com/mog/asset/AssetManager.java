package com.mog.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 资产管理器：统一的资源生命周期（加载 -> 缓存 -> 引用计数 -> 释放）。
 *
 * 解决的问题：
 *   - 同一资源多处使用只加载一份（同一 PNG 两个实体共享一个 GL 纹理）
 *   - 释放有据可依：引用计数归零才真正 dispose，杜绝"谁删谁背锅"
 *   - 为异步/流式加载留好入口（loadAsync；GL 资源创建仍需回主线程，见注释）
 *
 * key 约定：逻辑路径 + 可选参数，如 "/textures/grass.png?repeat"、"assets/models/duck.glb"。
 * 参数不同即不同资产（repeat 与 clamp 环绕的同一图片是两个 GL 纹理）。
 */
public class AssetManager {

    private static final Logger log = LoggerFactory.getLogger(AssetManager.class);

    /** 特定类型资产的加载/释放策略。 */
    public interface Loader<T> {
        T load(String key) throws Exception;

        void dispose(T asset);
    }

    private static final class Entry<T> {
        final T asset;
        final Loader<T> loader;
        int refCount;

        Entry(T asset, Loader<T> loader) {
            this.asset = asset;
            this.loader = loader;
        }
    }

    private final Map<String, Entry<?>> cache = new HashMap<>();
    private final Map<Class<?>, Loader<?>> loaders = new HashMap<>();
    /** IO/解码可异步；注意 GL 资源上传必须在主线程（loadAsync 返回后由主线程 acquire） */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "asset-loader");
        t.setDaemon(true);
        return t;
    });

    public <T> void registerLoader(Class<T> type, Loader<T> loader) {
        loaders.put(type, loader);
    }

    /**
     * 获取资产：命中缓存则引用计数 +1；未命中则同步加载。
     * 用完必须配对调用 release()。
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> T acquire(String key, Class<T> type) {
        Entry<T> entry = (Entry<T>) cache.get(key);
        if (entry == null) {
            Loader<T> loader = (Loader<T>) loaders.get(type);
            if (loader == null) {
                throw new IllegalArgumentException("未注册加载器: " + type.getSimpleName());
            }
            try {
                T asset = loader.load(key);
                entry = new Entry<>(asset, loader);
                cache.put(key, entry);
                log.debug("资产加载: {} ({})", key, type.getSimpleName());
            } catch (Exception e) {
                throw new IllegalStateException("资产加载失败: " + key, e);
            }
        }
        entry.refCount++;
        return entry.asset;
    }

    /** 释放一次引用；计数归零时真正 dispose 并移出缓存。 */
    public synchronized void release(String key) {
        Entry<?> entry = cache.get(key);
        if (entry == null) {
            log.warn("release 未持有的资产: {}", key);
            return;
        }
        if (--entry.refCount <= 0) {
            disposeEntry(entry);
            cache.remove(key);
            log.debug("资产释放: {}", key);
        }
    }

    /**
     * 异步预加载：IO/解码在工作线程完成后进缓存（refCount=0 挂起态），
     * 主线程随后 acquire() 即秒中缓存。适合区块流式加载、场景预热。
     */
    public synchronized <T> CompletableFuture<Void> preloadAsync(String key, Class<T> type) {
        if (cache.containsKey(key)) {
            return CompletableFuture.completedFuture(null);
        }
        @SuppressWarnings("unchecked")
        Loader<T> loader = (Loader<T>) loaders.get(type);
        if (loader == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("未注册加载器: " + type.getSimpleName()));
        }
        // 占位防重复提交（asset 为 null 的 Entry 表示"加载中"）
        return CompletableFuture.runAsync(() -> {
            try {
                T asset = loader.load(key);
                synchronized (this) {
                    if (!cache.containsKey(key)) {
                        cache.put(key, new Entry<>(asset, loader));
                    }
                }
                log.debug("资产预加载完成: {}", key);
            } catch (Exception e) {
                log.error("资产预加载失败: {}", key, e);
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void disposeEntry(Entry<?> entry) {
        if (entry.asset != null) {
            ((Loader) entry.loader).dispose(entry.asset);
        }
    }

    /** 强制释放全部缓存资产（场景切换兜底/引擎退出）。 */
    public synchronized void disposeAll() {
        for (Map.Entry<String, Entry<?>> e : cache.entrySet()) {
            disposeEntry(e.getValue());
        }
        int n = cache.size();
        cache.clear();
        log.info("资产管理器已清空 ({} 项)", n);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public synchronized int getCacheSize() {
        return cache.size();
    }
}
