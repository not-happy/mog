#version 330 core

// UI 文本片元：字体图集是单通道(R8)覆盖度蒙版，与顶点色相乘
in vec2 vUv;
in vec4 vColor;

uniform sampler2D fontAtlas;

out vec4 outColor;

void main() {
    float coverage = texture(fontAtlas, vUv).r;
    outColor = vec4(vColor.rgb, vColor.a * coverage);
}
