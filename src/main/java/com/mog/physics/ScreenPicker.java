package com.mog.physics;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * 屏幕拾取工具（C3）：鼠标窗口坐标 -> 世界射线 -> 与水平面求交。静态无状态。
 *
 * 与 {@link Ray}（slab 法 AABB 求交）互补：本类补"反投影 + 射线-平面"两块缺口，
 * Camera 与 Ray 均不改动。手动 invert + transformProject 到调用方提供的 scratch，
 * 不用 JOML 的 unprojectRay（内部隐式分配，违反零每帧分配约定）。
 */
public final class ScreenPicker {

    /** dir.y 小于此值视为与水平面平行（除零守卫） */
    private static final float PARALLEL_EPS = 1e-6f;
    /** 投影矩阵行列式小于此值视为奇异（不可逆守卫） */
    private static final float SINGULAR_EPS = 1e-9f;

    private ScreenPicker() {
    }

    /**
     * 鼠标窗口坐标 -> NDC。
     *
     * GLFW 窗口坐标原点在左上、y 向下；NDC 原点在中心、y 向上。
     * 窗口逻辑坐标与 framebuffer 像素的比例在换算中抵消（ndc = 2*m/win - 1），
     * 高分屏缩放无需显式处理。
     *
     * @return false 表示窗口尺寸非法（结果未写入）
     */
    public static boolean windowToNdc(double mouseX, double mouseY,
                                      int winW, int winH, Vector2f out) {
        if (winW <= 0 || winH <= 0) {
            return false;
        }
        out.x = (float) (2.0 * mouseX / winW - 1.0);
        out.y = (float) (1.0 - 2.0 * mouseY / winH);
        return true;
    }

    /**
     * 由 projection×view 矩阵反投影出世界空间射线。
     *
     * @param projView   projection × view（调用方组合）
     * @param invScratch 可逆矩阵暂存（调用方提供，避免每帧分配）
     * @param outOrigin  射线原点（近平面点）
     * @param outDir     射线方向（单位化，指向远平面）
     * @return false 表示矩阵奇异，射线未写入
     */
    public static boolean unprojectRay(Matrix4f projView, Matrix4f invScratch,
                                       float ndcX, float ndcY,
                                       Vector3f outOrigin, Vector3f outDir) {
        if (Math.abs(projView.determinant()) < SINGULAR_EPS) {
            return false;
        }
        projView.invert(invScratch);
        // NDC z=-1 -> 近平面点，z=+1 -> 远平面点；transformProject 含透视除法
        invScratch.transformProject(outOrigin.set(ndcX, ndcY, -1f));
        invScratch.transformProject(outDir.set(ndcX, ndcY, 1f));
        outDir.sub(outOrigin).normalize();
        return true;
    }

    /**
     * 射线与水平面 y=planeY 求交。
     *
     * @return 命中距离 t；射线平行 / 交点在背后 / 超过 maxDist 时返回 NaN
     */
    public static float rayPlaneY(Vector3f origin, Vector3f dir, float planeY, float maxDist) {
        if (Math.abs(dir.y) < PARALLEL_EPS) {
            return Float.NaN;
        }
        float t = (planeY - origin.y) / dir.y;
        if (t <= 0f || t > maxDist) {
            return Float.NaN;
        }
        return t;
    }
}
