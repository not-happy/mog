package com.mog.render;

/**
 * 基础几何体工厂：生成常用网格（纯静态工具，不持有状态）。
 */
public final class Shapes {

    // @formatter:off
    /** 单位立方体顶点位置：24 顶点（每面 4 个，法线不共享），每面顺序（从面外看）：左下、右下、右上、左上 */
    private static final float[] CUBE_POSITIONS = {
            // 前面 +Z
            -0.5f, -0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // 后面 -Z
             0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
            // 右面 +X
             0.5f, -0.5f,  0.5f,   0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,
            // 左面 -X
            -0.5f, -0.5f, -0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
            // 上面 +Y
            -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
            // 下面 -Y
            -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
    };
    private static final float[] CUBE_NORMALS = {
             0, 0, 1,   0, 0, 1,   0, 0, 1,   0, 0, 1,
             0, 0,-1,   0, 0,-1,   0, 0,-1,   0, 0,-1,
             1, 0, 0,   1, 0, 0,   1, 0, 0,   1, 0, 0,
            -1, 0, 0,  -1, 0, 0,  -1, 0, 0,  -1, 0, 0,
             0, 1, 0,   0, 1, 0,   0, 1, 0,   0, 1, 0,
             0,-1, 0,   0,-1, 0,   0,-1, 0,   0,-1, 0,
    };
    // @formatter:on
    /** 每面的 UV：左下(0,0) 右下(1,0) 右上(1,1) 左上(0,1) */
    private static final float[] CUBE_TEX_COORDS = new float[8 * 6];
    private static final int[] CUBE_INDICES = new int[36];

    static {
        float[] quadUv = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int face = 0; face < 6; face++) {
            System.arraycopy(quadUv, 0, CUBE_TEX_COORDS, face * quadUv.length, quadUv.length);
            int v = face * 4;
            int i = face * 6;
            CUBE_INDICES[i] = v;
            CUBE_INDICES[i + 1] = v + 1;
            CUBE_INDICES[i + 2] = v + 2;
            CUBE_INDICES[i + 3] = v + 2;
            CUBE_INDICES[i + 4] = v + 3;
            CUBE_INDICES[i + 5] = v;
        }
    }

    private Shapes() {
    }

    /** 创建单位立方体（边长 1，中心在原点），含位置、法线、纹理坐标与索引。 */
    public static Mesh createCube() {
        return new Mesh(CUBE_POSITIONS, null, CUBE_TEX_COORDS, CUBE_NORMALS, CUBE_INDICES);
    }

    /** 创建纯色单位立方体（走顶点色管线，适合做光源标记等无光照小物件）。 */
    public static Mesh createSolidColorCube(float r, float g, float b) {
        float[] colors = new float[CUBE_POSITIONS.length]; // 与位置同为 24 顶点 × 3 分量
        for (int i = 0; i < colors.length / 3; i++) {
            colors[i * 3] = r;
            colors[i * 3 + 1] = g;
            colors[i * 3 + 2] = b;
        }
        return new Mesh(CUBE_POSITIONS, colors, null, CUBE_NORMALS, CUBE_INDICES);
    }

    /**
     * 创建 XZ 平面（地面），中心在原点，法线朝上 (+Y)。
     *
     * @param size     边长
     * @param uvScale  UV 重复次数（配合 repeat 纹理实现平铺，如 60 大小地面用 30 表示每 2 单位平铺一次）
     */
    public static Mesh createPlane(float size, float uvScale) {
        float s = size / 2.0f;
        // 从上方看为 CCW 顺序（正面朝上）
        float[] positions = {
                -s, 0,  s,
                 s, 0,  s,
                 s, 0, -s,
                -s, 0, -s,
        };
        float[] normals = {
                0, 1, 0,
                0, 1, 0,
                0, 1, 0,
                0, 1, 0,
        };
        float[] texCoords = {
                0, 0,
                uvScale, 0,
                uvScale, uvScale,
                0, uvScale,
        };
        int[] indices = {0, 1, 2, 2, 3, 0};
        return new Mesh(positions, null, texCoords, normals, indices);
    }
}
