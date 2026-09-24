package com.mog.game;

import com.mog.astro.EpochType;

/**
 * 纪元调色板（GDD D7）：每个纪元一套 太阳色 / 色调分级色 / 分级强度 / 光强系数。
 *
 * 四主纪元色（序曜暖金 / 乱曜绯红 / 寒曜冷蓝 / 烈曜灼白）来自 GDD D7 定稿；
 * 掠曜 / 三曜凌空 / 失家 为临时值，标注"待GDD确认"。
 *
 * 消费方：SurfaceSky（太阳色 × 光强系数 -> 方向光）与 SurfaceScene
 * （色调分级 -> PostProcessor.setTint，显示空间末端乘法染色）。
 * 纯数据枚举，无逻辑。
 */
public enum EpochPalette {

    /** 序曜期：单星稳定照耀——暖金（GDD D7） */
    XU_YAO(
            new float[]{1.00f, 0.86f, 0.62f},
            new float[]{1.00f, 0.90f, 0.72f}, 0.14f, 1.00f),
    /** 乱曜期：多星混沌——绯红（GDD D7） */
    LUAN_YAO(
            new float[]{1.00f, 0.55f, 0.48f},
            new float[]{1.00f, 0.62f, 0.58f}, 0.18f, 1.00f),
    /** 寒曜：三星皆远——冷蓝（GDD D7），光强系数压暗 */
    HAN_YAO(
            new float[]{0.62f, 0.76f, 1.00f},
            new float[]{0.68f, 0.80f, 1.00f}, 0.22f, 0.75f),
    /** 烈曜：灼热灾难——灼白（GDD D7），光强系数 1.35 过曝感 */
    LIE_YAO(
            new float[]{1.00f, 0.98f, 0.92f},
            new float[]{1.00f, 0.97f, 0.90f}, 0.16f, 1.35f),
    /** 掠曜：恒星高速逼近——琥珀（临时值，待GDD确认） */
    LUE_YAO(
            new float[]{1.00f, 0.72f, 0.40f},
            new float[]{1.00f, 0.78f, 0.52f}, 0.16f, 1.10f),
    /** 三曜凌空：罕见奇观——紫白（临时值，待GDD确认） */
    SAN_YAO(
            new float[]{0.85f, 0.78f, 1.00f},
            new float[]{0.88f, 0.82f, 1.00f}, 0.20f, 1.25f),
    /** 失家深空：无宿主星——深蓝弱光（临时值，待GDD确认；
     *  光强系数即绝对弱光强度，SurfaceSky 不再乘温度） */
    SHI_JIA(
            new float[]{0.25f, 0.35f, 0.60f},
            new float[]{0.35f, 0.45f, 0.72f}, 0.30f, 0.18f);

    private final float[] sunColor;
    private final float[] tintColor;
    private final float tintStrength;
    private final float intensityScale;

    EpochPalette(float[] sunColor, float[] tintColor, float tintStrength, float intensityScale) {
        this.sunColor = sunColor;
        this.tintColor = tintColor;
        this.tintStrength = tintStrength;
        this.intensityScale = intensityScale;
    }

    /** 纪元类型 -> 调色板。 */
    public static EpochPalette of(EpochType type) {
        return switch (type) {
            case ORDER_YAO -> XU_YAO;
            case CHAOS_YAO -> LUAN_YAO;
            case SCORCH -> LIE_YAO;
            case FREEZE -> HAN_YAO;
            case FLYBY -> LUE_YAO;
            case SYZYGY -> SAN_YAO;
            case LOST -> SHI_JIA;
        };
    }

    /** 太阳色（RGB，方向光颜色；只读）。 */
    public float[] getSunColor() {
        return sunColor;
    }

    /** 色调分级色（合成遍 tintColor；只读）。 */
    public float[] getTintColor() {
        return tintColor;
    }

    /** 色调分级强度 [0,1]。 */
    public float getTintStrength() {
        return tintStrength;
    }

    /** 光强系数（乘在温度推出的基础强度上；失家 = 绝对弱光强度）。 */
    public float getIntensityScale() {
        return intensityScale;
    }
}
