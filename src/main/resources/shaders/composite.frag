#version 330 core

// 合成遍（HDR 管线终点）：场景(HDR) + 泛光(HDR) -> Reinhard 色调映射 -> gamma 编码
//                        -> 暗角 -> 饱和度，输出到屏幕（LDR 显示空间）
in vec2 vTexCoord;

uniform sampler2D sceneTexture;   // RGBA16F，线性 HDR，可能 >1.0
uniform sampler2D bloomTexture;   // RGBA16F
uniform float bloomStrength;      // 0 = 关闭泛光（后处理禁用时的直通路）
uniform float vignetteStrength;   // 0 = 无暗角
uniform float saturation;         // 1 = 原色
uniform float exposure;           // 曝光：tonemap 前整体缩放 HDR 亮度

out vec4 outColor;

void main() {
    vec3 color = texture(sceneTexture, vTexCoord).rgb;

    // 泛光叠加（仍在 HDR 线性空间——这是 HDR 管线的关键收益：
    // 超过 1.0 的亮部信息参与了混合，而不是被提前截断）
    color += texture(bloomTexture, vTexCoord).rgb * bloomStrength;

    // 曝光调整（HDR 空间）
    color *= exposure;

    // Reinhard 色调映射：把无界 HDR 压进 [0,1]（高光平滑滚降，不过曝）
    color = color / (color + vec3(1.0));

    // gamma 编码：线性光 -> sRGB 显示空间
    color = pow(color, vec3(1.0 / 2.2));

    // 以下在显示空间调整
    // 暗角：屏幕边缘压暗，中心保持
    if (vignetteStrength > 0.0) {
        vec2 uv = vTexCoord * (1.0 - vTexCoord);       // 边缘趋近 0，中心 0.25
        float vig = clamp(pow(uv.x * uv.y * 16.0, vignetteStrength), 0.0, 1.0);
        color *= vig;
    }

    // 饱和度：绕亮度轴插值
    if (saturation != 1.0) {
        float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
        color = mix(vec3(luma), color, saturation);
    }

    outColor = vec4(color, 1.0);
}
