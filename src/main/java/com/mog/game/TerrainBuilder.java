package com.mog.game;

import com.mog.render.Mesh;

/**
 * 地形生成工具（静态无状态）：缓起伏正弦丘陵 + 解析法线。
 *
 * 高度场是三频正弦叠加（频率翻倍、振幅减半），相位由种子经稳定散列导出——
 * 同一 (x, z, heightScale, seed) 永远得到同一高度，地形网格 / 建造网格线 /
 * 相机 / 建筑落位多方查询天然一致，无需共享实例。
 *
 * 实体类不放逻辑的项目规则：本类是纯工具，不持有任何状态。
 */
public final class TerrainBuilder {

    // 三个八度：振幅和 = 1.0，heightAt 值域 ±heightScale
    private static final float A1 = 0.55f;
    private static final float A2 = 0.30f;
    private static final float A3 = 0.15f;
    private static final float F1X = 0.09f;
    private static final float F1Z = 0.08f;
    private static final float F2X = 0.19f;
    private static final float F2Z = 0.17f;
    private static final float F3X = 0.41f;
    private static final float F3Z = 0.37f;

    private TerrainBuilder() {
    }

    /** 世界坐标 (x, z) 处的地形高度（米）。 */
    public static float heightAt(float x, float z, float heightScale, long seed) {
        float h = A1 * sin(x * F1X + phase(seed, 1)) * cos(z * F1Z + phase(seed, 2))
                + A2 * sin(x * F2X - z * F2Z + phase(seed, 3))
                + A3 * sin(x * F3X + phase(seed, 4)) * sin(z * F3Z + phase(seed, 5));
        return h * heightScale;
    }

    /**
     * 生成 (cells+1)² 顶点的网格地形（lit 管线用：位置 + 解析法线 + 索引，
     * 顶点色为 null——颜色走 MaterialComponent 的 materialColor uniform）。
     * 三角形绕序保证法线朝 +Y（背面剔除下从上可见）。
     */
    public static Mesh build(int cells, float cellSize, float heightScale, long seed) {
        int n = cells + 1;
        float half = cells * cellSize / 2f;
        float[] positions = new float[n * n * 3];
        float[] normals = new float[n * n * 3];

        float p1 = phase(seed, 1);
        float p2 = phase(seed, 2);
        float p3 = phase(seed, 3);
        float p4 = phase(seed, 4);
        float p5 = phase(seed, 5);

        for (int iz = 0; iz < n; iz++) {
            float z = -half + iz * cellSize;
            for (int ix = 0; ix < n; ix++) {
                float x = -half + ix * cellSize;
                int v = (iz * n + ix) * 3;

                float s1 = sin(x * F1X + p1);
                float c1 = cos(x * F1X + p1);
                float s2 = sin(z * F1Z + p2);
                float c2 = cos(z * F1Z + p2);
                float s3 = sin(x * F2X - z * F2Z + p3);
                float c3 = cos(x * F2X - z * F2Z + p3);
                float s4 = sin(x * F3X + p4);
                float c4 = cos(x * F3X + p4);
                float s5 = sin(z * F3Z + p5);
                float c5 = cos(z * F3Z + p5);

                positions[v] = x;
                positions[v + 1] = (A1 * s1 * c2 + A2 * s3 + A3 * s4 * s5) * heightScale;
                positions[v + 2] = z;

                // 解析偏导 ∂h/∂x、∂h/∂z -> 法线 (-∂h/∂x, 1, -∂h/∂z) 归一化
                float dhdx = (A1 * F1X * c1 * c2 + A2 * F2X * c3 + A3 * F3X * c4 * s5) * heightScale;
                float dhdz = (-A1 * F1Z * s1 * s2 - A2 * F2Z * c3 + A3 * F3Z * s4 * c5) * heightScale;
                float nx = -dhdx;
                float nz = -dhdz;
                float inv = 1f / (float) Math.sqrt(nx * nx + 1f + nz * nz);
                normals[v] = nx * inv;
                normals[v + 1] = inv;
                normals[v + 2] = nz * inv;
            }
        }

        int[] indices = new int[cells * cells * 6];
        int p = 0;
        for (int iz = 0; iz < cells; iz++) {
            for (int ix = 0; ix < cells; ix++) {
                int a = iz * n + ix;
                int b = a + n;          // (ix, iz+1)
                int c = a + 1;          // (ix+1, iz)
                int d = c + n;          // (ix+1, iz+1)
                indices[p++] = a;
                indices[p++] = b;
                indices[p++] = c;
                indices[p++] = c;
                indices[p++] = b;
                indices[p++] = d;
            }
        }
        return new Mesh(positions, null, null, normals, indices);
    }

    /** 种子散列 -> [0, 2π) 相位（splitmix 风格；纯函数，无 Random 实例分配）。 */
    private static float phase(long seed, int octave) {
        long h = seed * 0x9E3779B97F4A7C15L + octave * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (float) ((h & 0xFFFFFFL) / (double) 0x1000000L * (2.0 * Math.PI));
    }

    private static float sin(float v) {
        return (float) Math.sin(v);
    }

    private static float cos(float v) {
        return (float) Math.cos(v);
    }
}
