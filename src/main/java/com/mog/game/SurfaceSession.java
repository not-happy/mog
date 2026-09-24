package com.mog.game;

import com.mog.core.event.BuildingPlacedEvent;
import com.mog.core.event.BuildingRemovedEvent;
import com.mog.core.event.EventBus;
import com.mog.core.event.ResourceDepletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 地表会话（C3）：地表逻辑的 Game 级唯一真源——纯 Java、零 GL 依赖，仿
 * {@link CosmosSession} 先例。收敛：占位网格（存**逻辑占位 id**）、建筑表、
 * 资源节点表与储量、库存、采集者状态机（批 2）、生产 tick（批 3）。
 *
 * <p><b>D5 铁律的落点</b>：Game 固定步长循环里 {@code cosmos.tick -> surface.tick ->
 * scene.update}——宇宙视角时地表模拟继续运行；场景（SurfaceScene）降级为视图薄壳，
 * init 时从本会话全量重建实体并维护"逻辑 id -> 实体 id"私有映射（易失、随场景重建），
 * 因此逻辑 id 跨 Tab 恒定、实体 id 不跨 Tab——建筑不再"Tab 往返即丢"。
 *
 * <p>事件红线：场景零事件（连"发"都不做）——放置/拆除/枯竭事件全部由本会话发布，
 * 订阅一律在 Game.wireEvents。
 *
 * <p>时间标尺（GDD D10）：tick 收到的 dt 是固定步长（1/60 秒），内部换算模拟时间
 * sdt = dt × 会话倍速（钳制 {@link #MAX_SURFACE_DT} 防 120 倍速单步穿墙，批 2 另有
 * 位移子步护栏）。暂停/乐章终结时冻结（与 CosmosSession 行为一致）。
 */
public final class SurfaceSession {

    private static final Logger log = LoggerFactory.getLogger(SurfaceSession.class);

    // ===== 规模与节拍常量（设计定稿，护栏不可调大：WARP draw 预算）=====
    /** 资源节点上限（最坏 draw 预算护栏） */
    public static final int MAX_NODES = 28;
    /** 采集者上限 */
    public static final int MAX_HARVESTERS = 12;
    /** 开局库存矿石 */
    public static final int STARTING_ORE = 0;
    /** 单趟携带上限（矿） */
    public static final int HARVESTER_CAP_PER_TRIP = 4;
    /** 步行速度（米/模拟时间单位） */
    public static final float WALK_SPEED = 5.0f;
    /** 单次采集工时（模拟时间单位，受纪元衰减倍率） */
    public static final float MINE_TIME = 1.0f;
    /** 单次卸货工时 */
    public static final float UNLOAD_TIME = 0.4f;
    /** 抵达判定距离（米）：流场下降终止于贴邻，双条件之一 */
    public static final float ARRIVE_DIST = 2.5f;
    /** 单步模拟时间上限（speed=120 时 dt×speed=2.0） */
    public static final float MAX_SURFACE_DT = 2.0f;
    /** 地形起伏幅度（米）：从场景上收——生成器/会话/场景三方一致性靠它 */
    public static final float HEIGHT_SCALE = 0.25f;
    /** 初始预置指挥中枢的格坐标（网格中心 2×2 足迹最小角） */
    public static final int HUB_CELL_X = 15;
    public static final int HUB_CELL_Z = 15;

    private final EventBus eventBus;
    private final CosmosSession cosmos;

    // ===== 逻辑真源（跨场景持久）=====
    /** 占位网格：存"逻辑占位 id + 1"（建筑与节点共用 id 空间，跨 Tab 恒定） */
    private final OccupancyGrid occupancy = new OccupancyGrid();
    private final List<BuildingRecord> buildings = new ArrayList<>();
    private final List<ResourceNode> nodes = new ArrayList<>();
    private final List<HarvesterAgent> harvesters = new ArrayList<>();
    /** 库存：ResourceType.ordinal() 索引 */
    private final int[] inventory = new int[ResourceType.values().length];
    /** 逻辑 id 单调发放器（只增不减——拆除不回收，防旧占位/缓存串号） */
    private int nextLogicalId;
    private long terrainSeed;

    public SurfaceSession(EventBus eventBus, CosmosSession cosmos) {
        this.eventBus = eventBus;
        this.cosmos = cosmos;
        reset(deriveTerrainSeed(cosmos.getSim().getSeed()));
    }

    /** 乐章种子 -> 地形种子（从 SurfaceScene 上收，公式不变：同乐章地表稳定）。 */
    public static long deriveTerrainSeed(long runSeed) {
        return runSeed * 31 + 7;
    }

    /**
     * 全量复位（新乐章/重开）：占位、三表、库存、id 发放器清零后，
     * 预置指挥中枢（不发事件，避免启动噪音）并按种子生成资源节点。
     */
    public void reset(long terrainSeed) {
        this.terrainSeed = terrainSeed;
        occupancy.reset();
        buildings.clear();
        nodes.clear();
        harvesters.clear();
        Arrays.fill(inventory, 0);
        inventory[ResourceType.ORE.ordinal()] = STARTING_ORE;
        nextLogicalId = 0;

        placeBuilding(BuildingType.COMMAND_HUB, HUB_CELL_X, HUB_CELL_Z, false);
        ResourceNodeGenerator.generate(terrainSeed, occupancy, ignored -> nextLogicalId++, nodes);
        log.info("地表会话复位: 地形种子={} 建筑={} 节点={} 库存矿石={}",
                terrainSeed, buildings.size(), nodes.size(), getOre());
    }

    /**
     * 固定步长 tick（Game 主循环，cosmos.tick 之后、scene.update 之前）。
     * 批 1：冻结判定骨架；批 2 接入采集者状态机/流场重建；批 3 接入生产链。
     */
    public void tick(float dt) {
        if (cosmos.isPaused() || cosmos.isRunEnded()) {
            return; // 冻结：暂停/乐章终结时地表停摆（与宇宙会话一致）
        }
        // float sdt = Math.min(dt * cosmos.getSpeed(), MAX_SURFACE_DT);
        // float k = getYieldFactor();                 // 纪元衰减（批 3：EpochYield 现读）
        // stepHarvesters(sdt, k);                     // 批 2
        // tickProduction(sdt, k);                     // 批 3
        // rebuildDirtyFields();                       // 批 2
    }

    // ===== 变更 API（原子序：校验全过才落地，失败无副作用）=====

    /**
     * 放置建筑：canPlace -> canAfford -> 扣矿 -> 占位 -> 记录 -> 事件（announce 时）。
     *
     * @return 新建筑逻辑 id；任一校验失败返回 -1（无副作用）
     */
    public int placeBuilding(BuildingType type, int cellX, int cellZ, boolean announce) {
        if (!occupancy.canPlace(cellX, cellZ, type.getFootW(), type.getFootD())) {
            return -1;
        }
        // 批 3 将 costCrystal 改名 costOre 并赋非零值——扣费通路批 1 就位（当前恒 0）
        int cost = type.getCostCrystal();
        if (!canAfford(cost)) {
            return -1;
        }
        inventory[ResourceType.ORE.ordinal()] -= cost;
        int id = nextLogicalId++;
        occupancy.place(cellX, cellZ, type.getFootW(), type.getFootD(), id);
        buildings.add(new BuildingRecord(id, type, cellX, cellZ));
        if (announce) {
            eventBus.publish(new BuildingPlacedEvent(
                    id, type.name(), cellX, cellZ, type.getFootW(), type.getFootD()));
        }
        return id;
    }

    /**
     * 拆除光标格上的建筑（多格足迹任一覆盖格均可）。
     * 资源节点不可拆（占位命中但无建筑记录 -> false）；拆除不退款（批 3 注释定稿）。
     */
    public boolean removeBuildingAt(int cellX, int cellZ) {
        int logical = occupancy.entityAt(cellX, cellZ);
        if (logical < 0) {
            return false;
        }
        BuildingRecord victim = null;
        for (BuildingRecord b : buildings) {
            if (b.logicalId() == logical) {
                victim = b;
                break;
            }
        }
        if (victim == null) {
            return false; // 资源节点：不可拆
        }
        occupancy.clear(victim.cellX(), victim.cellZ(),
                victim.type().getFootW(), victim.type().getFootD());
        buildings.remove(victim);
        eventBus.publish(new BuildingRemovedEvent(
                victim.logicalId(), victim.type().name(), victim.cellX(), victim.cellZ()));
        return true;
    }

    /**
     * 部署采集者（自由坐标，不占格不扣费）。批 1：落位 + 指派最近节点；
     * 批 2 接入状态机搬运循环。
     *
     * @return 新采集者逻辑 id；超上限或落点格被占返回 -1
     */
    public int deployHarvester(float worldX, float worldZ) {
        if (harvesters.size() >= MAX_HARVESTERS) {
            return -1;
        }
        if (!BuildGrid.inBounds(worldX, worldZ)) {
            return -1;
        }
        // inBounds 先于 worldToCell（钳制陷阱）：落点格必须空闲（不压建筑/节点）
        int cx = BuildGrid.worldToCell(worldX);
        int cz = BuildGrid.worldToCell(worldZ);
        if (!isOccupiedFree(worldX, worldZ)) {
            return -1;
        }
        int id = nextLogicalId++;
        float y = TerrainBuilder.heightAt(worldX, worldZ, HEIGHT_SCALE, terrainSeed);
        HarvesterAgent agent = new HarvesterAgent(id, worldX, worldZ, y);
        agent.cellX = cx;
        agent.cellZ = cz;
        agent.targetNode = findNearestNode(agent);
        harvesters.add(agent);
        return id;
    }

    // ===== 只读查询 =====

    /** 世界坐标落点格是否空闲（inBounds 由调用方先行守卫；越界视为不可用）。 */
    public boolean isOccupiedFree(float worldX, float worldZ) {
        if (!BuildGrid.inBounds(worldX, worldZ)) {
            return false;
        }
        return occupancy.entityAt(BuildGrid.worldToCell(worldX),
                BuildGrid.worldToCell(worldZ)) < 0;
    }

    public boolean canPlace(int cellX, int cellZ, int footW, int footD) {
        return occupancy.canPlace(cellX, cellZ, footW, footD);
    }

    public boolean canAfford(int ore) {
        return inventory[ResourceType.ORE.ordinal()] >= ore;
    }

    public int getOre() {
        return inventory[ResourceType.ORE.ordinal()];
    }

    public int getHarvesterCount() {
        return harvesters.size();
    }

    /**
     * 纪元衰减倍率（作用于工时速率）。批 3 接 EpochYield 现读纪元——
     * 红线：每 tick 现读，不得跨帧缓存。批 1 恒 1.0。
     */
    public float getYieldFactor() {
        return 1.0f;
    }

    public long getTerrainSeed() {
        return terrainSeed;
    }

    public List<BuildingRecord> getBuildings() {
        return buildings;
    }

    public List<ResourceNode> getNodes() {
        return nodes;
    }

    public List<HarvesterAgent> getHarvesters() {
        return harvesters;
    }

    /** 按逻辑 id 查节点（视图系统每帧调用；线性扫 ≤28，无表必要）。 */
    public ResourceNode nodeById(int logicalId) {
        for (ResourceNode n : nodes) {
            if (n.id == logicalId) {
                return n;
            }
        }
        return null;
    }

    /** 按逻辑 id 查采集者（视图系统每帧调用；线性扫 ≤12）。 */
    public HarvesterAgent agentById(int logicalId) {
        for (HarvesterAgent a : harvesters) {
            if (a.id == logicalId) {
                return a;
            }
        }
        return null;
    }

    /** 光标格占位反查（场景拆除前查实体映射用）。 */
    public int logicalAt(int cellX, int cellZ) {
        return occupancy.entityAt(cellX, cellZ);
    }

    // ===== 内部工具 =====

    /** 最近未枯竭节点（距离平方线性扫，无需开方）；全枯/无节点返回 -1。 */
    private int findNearestNode(HarvesterAgent agent) {
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (ResourceNode n : nodes) {
            if (n.depleted) {
                continue;
            }
            float dx = n.worldX - agent.x;
            float dz = n.worldZ - agent.z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = n.id;
            }
        }
        return best;
    }

    /** 节点枯竭（批 2 状态机触发）：置位 + 发事件。 */
    void markDepleted(ResourceNode node) {
        node.depleted = true;
        eventBus.publish(new ResourceDepletedEvent(node.id, node.cellX, node.cellZ));
    }
}
