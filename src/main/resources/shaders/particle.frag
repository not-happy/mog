#version 330 core

// 粒子片元：用公告板局部 UV 裁出软边圆点，配合加色混合发光
in vec2 vUv;
in vec4 vColor;

out vec4 outColor;

void main() {
    float d = length(vUv);
    float alpha = smoothstep(1.0, 0.3, d);   // 边缘软化
    if (alpha < 0.01) {
        discard;
    }
    outColor = vec4(vColor.rgb, vColor.a * alpha);
}
