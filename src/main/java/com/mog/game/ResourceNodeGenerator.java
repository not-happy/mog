package com.mog.game;

import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;

/**
 * 资源节点生成器（C3）：种子驱动的拒绝采样，纯静态工具（实体类不放逻辑）。
 *
 * 规则：
 * - 数量上限 {@link SurfaceSession#MAX_NODES}；储量 ORE_MIN..ORE_MIN+ORE_RANGE（40..120）
 * - **保底节点**：中枢切比雪夫距离（到 2×2 足迹块）3..5 格环内至少 1 个——
 *   垂直切片剧本要求"开局近矿"；候选格乱序取首个有效（越界/被占即天然跳过）
 * - 间距：节点两两切比雪夫 ≥ NODE_SPACING；距中枢块 > HUB_EXCLUSION；边距 ≥ EDGE_MARGIN
 * - 生成即占格：occ.place(cx,cz,1,1,node.id)——调用前 occ 必须已含中枢占位
 * - 逻辑 id 经 idSupplier 从会话统一发放（与建筑共用 id 空间）
 *
 * 确定性：同一种子 + 同一初始占位 => 完全相同的节点序列（java.util.Random 契约）。
 */
public final class ResourceNodeGenerator {

    /** 中枢锚点格（与 SurfaceSession 预置一致，2×2 足迹最小角） */
    public static final int HUB_ANCHOR_X = SurfaceSession.HUB_CELL_X;
    public static final int HUB_ANCHOR_Z = SurfaceSession.HUB_CELL_Z;
    /** 节点距中枢块的最小切比雪夫距离（严格大于） */
    public static final int HUB_EXCLUSION = 2;
    /** 节点两两最小切比雪夫间距 */
    public static final int NODE_SPACING = 2;
    /** 网格边缘留白（格） */
    public static final int EDGE_MARGIN = 1;
    public static final int ORE_MIN = 40;
    public static final int ORE_RANGE = 80;
    /** 拒绝采样上限（每槽位），防退化种子死循环 */
    public static final int MAX_ATTEMPTS = 200;

    private ResourceNodeGenerator() {
    }

    /**
     * 生成节点并追加到 out（同时占格）。
     *
     * @param seed       地形种子（同时用于分布采样与 heightAt 贴地）
     * @param occ        占位网格（调用前须已含中枢占位；本方法写入节点占位）
     * @param idSupplier 逻辑 id 发放器（入参忽略，每次调用返回下一个 id）
     * @param out        输出列表（会话节点表）
     */
    public static void generate(long seed, OccupancyGrid occ,
                                IntUnaryOperator idSupplier, List<ResourceNode> out) {
        Random rnd = new Random(seed);

        // ===== 保底节点：中枢块切比雪夫 3..5 格环，候选乱序取首个有效 =====
        int gx = -1;
        int gz = -1;
        List<int[]> ring = ringCandidates(HUB_ANCHOR_X, HUB_ANCHOR_Z,
                HUB_EXCLUSION + 1, HUB_EXCLUSION + 3);
        java.util.Collections.shuffle(ring, rnd);
        for (int[] c : ring) {
            if (isValid(occ, c[0], c[1], out)) {
                gx = c[0];
                gz = c[1];
                break;
            }
        }
        if (gx >= 0) {
            out.add(makeNode(gx, gz, seed, rnd, occ, idSupplier));
        }

        // ===== 拒绝采样填充到上限（每槽位 MAX_ATTEMPTS 次，耗尽即放弃）=====
        int span = BuildGrid.CELLS - 2 * EDGE_MARGIN;
        while (out.size() < SurfaceSession.MAX_NODES) {
            boolean placed = false;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                int cx = EDGE_MARGIN + rnd.nextInt(span);
                int cz = EDGE_MARGIN + rnd.nextInt(span);
                if (isValid(occ, cx, cz, out)) {
                    out.add(makeNode(cx, cz, seed, rnd, occ, idSupplier));
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                return; // 退化种子：网格几乎被占满，接受少于上限
            }
        }
    }

    private static ResourceNode makeNode(int cx, int cz, long seed, Random rnd,
                                         OccupancyGrid occ, IntUnaryOperator idSupplier) {
        int id = idSupplier.applyAsInt(0);
        float wx = BuildGrid.cellToWorld(cx);
        float wz = BuildGrid.cellToWorld(cz);
        float gy = TerrainBuilder.heightAt(wx, wz, SurfaceSession.HEIGHT_SCALE, seed);
        int ore = ORE_MIN + rnd.nextInt(ORE_RANGE + 1);
        ResourceNode node = new ResourceNode(id, cx, cz, wx, wz, gy, ore);
        occ.place(cx, cz, 1, 1, id);
        return node;
    }

    /** 候选格：到 2×2 中枢块切比雪夫距离 ∈ [minDist, maxDist] 且在边距内。 */
    private static List<int[]> ringCandidates(int anchorX, int anchorZ,
                                              int minDist, int maxDist) {
        List<int[]> cells = new java.util.ArrayList<>();
        for (int cx = EDGE_MARGIN; cx < BuildGrid.CELLS - EDGE_MARGIN; cx++) {
            for (int cz = EDGE_MARGIN; cz < BuildGrid.CELLS - EDGE_MARGIN; cz++) {
                int d = chebyshevToHubBlock(cx, cz, anchorX, anchorZ);
                if (d >= minDist && d <= maxDist) {
                    cells.add(new int[]{cx, cz});
                }
            }
        }
        return cells;
    }

    /** 格到 2×2 中枢块（锚点 anchorX..anchorX+1）的切比雪夫距离。 */
    static int chebyshevToHubBlock(int cx, int cz, int anchorX, int anchorZ) {
        int dx = Math.min(Math.abs(cx - anchorX), Math.abs(cx - anchorX - 1));
        int dz = Math.min(Math.abs(cz - anchorZ), Math.abs(cz - anchorZ - 1));
        return Math.max(dx, dz);
    }

    private static boolean isValid(OccupancyGrid occ, int cx, int cz, List<ResourceNode> out) {
        if (cx < EDGE_MARGIN || cz < EDGE_MARGIN
                || cx >= BuildGrid.CELLS - EDGE_MARGIN || cz >= BuildGrid.CELLS - EDGE_MARGIN) {
            return false;
        }
        if (chebyshevToHubBlock(cx, cz, HUB_ANCHOR_X, HUB_ANCHOR_Z) <= HUB_EXCLUSION) {
            return false;
        }
        for (ResourceNode n : out) {
            if (Math.max(Math.abs(cx - n.cellX), Math.abs(cz - n.cellZ)) < NODE_SPACING) {
                return false;
            }
        }
        return occ.canPlace(cx, cz, 1, 1);
    }
}
