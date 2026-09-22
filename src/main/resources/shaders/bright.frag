#version 330 core

// 泛光第一步：明亮提取——只保留亮度超过阈值的部分供后续模糊
in vec2 vTexCoord;

uniform sampler2D sceneTexture;
uniform float threshold;   // 亮度阈值（默认 0.75）
uniform float softKnee;    // 阈值软过渡宽度，避免亮度边界硬切

out vec4 outColor;

void main() {
    vec3 color = texture(sceneTexture, vTexCoord).rgb;
    float brightness = dot(color, vec3(0.2126, 0.7152, 0.0722)); // Rec.709 亮度
    // 软膝过渡：threshold 附近平滑衰减，而不是硬切
    float contrib = clamp((brightness - threshold) / max(softKnee, 1e-4), 0.0, 1.0);
    outColor = vec4(color * contrib, 1.0);
}
