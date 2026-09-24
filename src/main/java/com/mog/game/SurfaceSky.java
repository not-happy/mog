package com.mog.game;

import com.mog.astro.Epoch;
import com.mog.astro.EpochType;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * 地表天空工具（静态无状态）：把三体模拟换算成地表方向光（GDD D5 一致性铁律——
 * "宇宙视角看到的三星之舞，就是地表经历的天气"）。
 *
 * C2 骨架口径：
 *   - 宿主星取 {@link com.mog.astro.HostTracker}（滞回稳定，易天才换日，不逐帧抖）；
 *   - 恒星方向映射到地表 XZ 平面，仰角钳制 [25°, 65°]（C2 无昼夜循环，
 *     太阳永不落山——保证阴影始终可读）；
 *   - 强度 = clamp(温度指数 × 0.55, 0.35, 1.5) × 调色板光强系数；
 *   - 失家（宿主星 -1）：深蓝弱光，强度 = 调色板系数本身（0.18）。
 *
 * 实体类不放逻辑的项目规则：太阳是纯计算结果，写入调用方持有的 Light。
 */
public final class SurfaceSky {

    /** 仰角钳制（弧度）：C2 无昼夜，太阳始终在可读高度带内 */
    private static final float MIN_ELEVATION = (float) Math.toRadians(25f);
    private static final float MAX_ELEVATION = (float) Math.toRadians(65f);
    /** 温度指数 -> 基础光强的换算系数与钳制区间 */
    private static final double TEMP_TO_INTENSITY = 0.55;
    private static final double MIN_INTENSITY = 0.35;
    private static final double MAX_INTENSITY = 1.5;

    private SurfaceSky() {
    }

    /**
     * 计算当前时刻的地表方向光。
     *
     * @param session   宇宙会话（只读）
     * @param outDir    出参：光传播方向（Light.direction 同口径，着色器取反得指向光源方向）
     * @param outColor  出参：太阳色（调色板）
     * @return 光强
     */
    public static float computeSun(CosmosSession session, Vector3f outDir, Vector3f outColor) {
        Epoch epoch = session.getCurrentEpoch();
        EpochType type = epoch != null ? epoch.type() : EpochType.ORDER_YAO;
        int host = session.getHostTracker().getHost();

        // ===== 失家深空：无宿主星，天空只剩深蓝弱光 =====
        if (host < 0 || type == EpochType.LOST) {
            EpochPalette lost = EpochPalette.SHI_JIA;
            outDir.set(-0.2f, -1f, -0.3f).normalize();
            float[] c = lost.getSunColor();
            outColor.set(c[0], c[1], c[2]);
            return lost.getIntensityScale();
        }

        // ===== 宿主星方向：模拟空间 (星 − 行星) -> 地表 XZ + 钳制仰角 =====
        Vector3d star = session.getSim().getStarPos(host);
        Vector3d planet = session.getSim().getPlanetPos();
        double dx = star.x - planet.x;
        double dy = star.y - planet.y;
        double dz = star.z - planet.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        EpochPalette palette = EpochPalette.of(type);
        float[] c = palette.getSunColor();
        if (len < 1e-9) {
            // 零长度守卫：星与行星重合的数值奇点，给一盏天顶默认光
            outDir.set(0f, -1f, 0f);
            outColor.set(c[0], c[1], c[2]);
            return palette.getIntensityScale();
        }

        double horiz = Math.sqrt(dx * dx + dz * dz);
        float ux;
        float uz;
        if (horiz < 1e-9) {
            ux = 0f;
            uz = -1f;   // 恒星恰在天顶：任选水平朝向
        } else {
            ux = (float) (dx / horiz);
            uz = (float) (dz / horiz);
        }
        double elev = Math.asin(Math.max(-1.0, Math.min(1.0, dy / len)));
        elev = Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, elev));
        float ce = (float) Math.cos(elev);
        float se = (float) Math.sin(elev);

        // 指向太阳的单位向量 (ux·ce, se, uz·ce)；光传播方向取其反
        outDir.set(-ux * ce, -se, -uz * ce);
        outColor.set(c[0], c[1], c[2]);

        double temp = epoch.temperature();
        double base = Math.max(MIN_INTENSITY,
                Math.min(MAX_INTENSITY, temp * TEMP_TO_INTENSITY));
        return (float) (base * palette.getIntensityScale());
    }
}
