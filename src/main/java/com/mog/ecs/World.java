package com.mog.ecs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ECS 世界：实体注册表 + 组件仓库 + 系统调度器。
 *
 * B5 升级版存储（API 与教学版完全兼容）：
 *   - 每实体一个 64 位组件签名（bitmask），组合查询 O(1) 判定
 *   - 每组件类型一个稀疏集（sparse set）：
 *       sparse[entityId] = 稠密下标+1（0 表示无），dense/items 紧凑存放
 *       —— get/add/remove 均 O(1)，remove 用末尾交换保持紧凑（缓存友好）
 *   - view() 从"最小的候选集"出发按位掩码过滤：O(最小集大小)，
 *     结果列表按签名缓存复用（注意：返回的列表在下次同签名 view() 调用前有效，
 *     调用方应立即遍历，不要跨帧持有）
 *
 * 上限：组件类型 ≤ 64（受 long 位宽限制；需要更多时换 long[] 或 BitSet 签名）。
 */
public class World {

    private static final Logger log = LoggerFactory.getLogger(World.class);
    private static final int MAX_COMPONENT_TYPES = 64;

    /** 稀疏集：entityId <-> 紧凑数组的双向映射。 */
    private static final class SparseSet {
        int[] sparse = new int[128];
        int[] dense = new int[64];
        Object[] items = new Object[64];
        int size;

        void add(int id, Object component) {
            if (id >= sparse.length) {
                sparse = Arrays.copyOf(sparse, Math.max(id + 1, sparse.length * 2));
            }
            if (sparse[id] != 0) {          // 已有：原地替换
                items[sparse[id] - 1] = component;
                return;
            }
            if (size == dense.length) {
                dense = Arrays.copyOf(dense, dense.length * 2);
                items = Arrays.copyOf(items, items.length * 2);
            }
            sparse[id] = size + 1;
            dense[size] = id;
            items[size] = component;
            size++;
        }

        Object get(int id) {
            return (id < sparse.length && sparse[id] != 0) ? items[sparse[id] - 1] : null;
        }

        boolean contains(int id) {
            return id < sparse.length && sparse[id] != 0;
        }

        void remove(int id) {
            if (!contains(id)) {
                return;
            }
            int idx = sparse[id] - 1;
            int lastId = dense[size - 1];
            items[idx] = items[size - 1];
            dense[idx] = lastId;
            sparse[lastId] = idx + 1;
            items[size - 1] = null;
            dense[size - 1] = 0;
            sparse[id] = 0;
            size--;
        }
    }

    private final Map<Class<?>, Integer> typeIds = new HashMap<>();
    private final SparseSet[] sets = new SparseSet[MAX_COMPONENT_TYPES];
    /** entityId -> 组件签名位掩码（0 = 实体已销毁/不存在） */
    private long[] masks = new long[128];
    private final Map<Integer, String> names = new HashMap<>();
    private final List<GameSystem> systems = new ArrayList<>();
    /** view 结果缓存：签名 -> 复用列表（避免每帧分配） */
    private final Map<Long, List<Integer>> viewCache = new HashMap<>();
    private int nextId = 1;
    private int aliveCount;

    // ==================== 实体生命周期 ====================

    public int createEntity(String name) {
        int id = nextId++;
        if (id >= masks.length) {
            masks = Arrays.copyOf(masks, masks.length * 2);
        }
        names.put(id, name);
        aliveCount++;
        log.debug("创建实体 #{} ({})", id, name);
        return id;
    }

    public void destroyEntity(int id) {
        long mask = maskOf(id);
        if (mask == 0) {
            return;
        }
        long bits = mask;
        while (bits != 0) {
            int t = Long.numberOfTrailingZeros(bits);
            if (sets[t] != null) {
                sets[t].remove(id);
            }
            bits &= bits - 1; // 清除最低位
        }
        masks[id] = 0;
        names.remove(id);
        aliveCount--;
    }

    public String getName(int id) {
        return names.getOrDefault(id, "?");
    }

    public int getEntityCount() {
        return aliveCount;
    }

    // ==================== 组件读写 ====================

    /** 为实体挂载组件（同类型重复挂载会覆盖）。 */
    public <T extends Component> T addComponent(int id, T component) {
        int t = typeIdOf(component.getClass());
        if (sets[t] == null) {
            sets[t] = new SparseSet();
        }
        sets[t].add(id, component);
        masks[id] |= 1L << t;
        return component;
    }

    @SuppressWarnings("unchecked")
    public <T extends Component> T getComponent(int id, Class<T> type) {
        Integer t = typeIds.get(type);
        if (t == null || sets[t] == null) {
            return null;
        }
        return (T) sets[t].get(id);
    }

    public boolean hasComponent(int id, Class<?> type) {
        Integer t = typeIds.get(type);
        return t != null && (maskOf(id) & (1L << t)) != 0;
    }

    /**
     * 查询同时拥有全部给定组件类型的实体。
     * 返回的列表按签名缓存复用：立即遍历使用，勿跨帧持有引用。
     */
    public List<Integer> view(Class<?>... types) {
        long mask = 0;
        SparseSet smallest = null;
        for (Class<?> type : types) {
            Integer t = typeIds.get(type);
            if (t == null) {
                // 从未注册过的组件类型：查询结果必为空
                return viewCache.computeIfAbsent(-1L, k -> new ArrayList<>());
            }
            mask |= 1L << t;
            if (sets[t] == null) {
                return viewCache.computeIfAbsent(mask | Long.MIN_VALUE, k -> new ArrayList<>());
            }
            if (smallest == null || sets[t].size < smallest.size) {
                smallest = sets[t];
            }
        }
        List<Integer> result = viewCache.computeIfAbsent(mask, k -> new ArrayList<>());
        result.clear();
        if (smallest == null) {
            return result;
        }
        for (int i = 0; i < smallest.size; i++) {
            int id = smallest.dense[i];
            if ((masks[id] & mask) == mask) {
                result.add(id);
            }
        }
        return result;
    }

    // ==================== 系统调度 ====================

    public void addSystem(GameSystem system) {
        systems.add(system);
    }

    /** 每帧调用：按注册顺序依次执行所有系统。 */
    public void update(float deltaTime) {
        for (GameSystem system : systems) {
            system.update(this, deltaTime);
        }
    }

    // ==================== 内部 ====================

    private long maskOf(int id) {
        return (id > 0 && id < masks.length) ? masks[id] : 0;
    }

    private int typeIdOf(Class<?> type) {
        Integer id = typeIds.get(type);
        if (id != null) {
            return id;
        }
        int newId = typeIds.size();
        if (newId >= MAX_COMPONENT_TYPES) {
            throw new IllegalStateException("组件类型超过上限 " + MAX_COMPONENT_TYPES
                    + "（long 位掩码限制），需升级签名为 long[] 或 BitSet");
        }
        typeIds.put(type, newId);
        return newId;
    }
}
