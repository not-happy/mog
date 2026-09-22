#version 330 core

// 2D 精灵片元：纹理 × 色调（tint 做变色/闪白/半透明都用它）
in vec2 vUv;
in vec4 vColor;

uniform sampler2D texSampler;

out vec4 outColor;

void main() {
    outColor = texture(texSampler, vUv) * vColor;
}
