#version 330 core

// 程序化黄昏天空：按方向求色（HDR，太阳核心可达数十倍亮度），
// 渲染到立方体六个面即得环境贴图，免去外部 HDRI 资产依赖
in vec3 localPos;

uniform vec3 sunDir;   // 指向太阳的单位向量

out vec4 outColor;

void main() {
    vec3 dir = normalize(localPos);

    // 天顶->地平线->地面 三段渐变（黄昏：天顶深蓝，地平线暖橙）
    vec3 zenith  = vec3(0.02, 0.04, 0.12);
    vec3 horizon = vec3(0.55, 0.30, 0.18);
    vec3 ground  = vec3(0.010, 0.012, 0.015);

    vec3 col;
    if (dir.y > 0.0) {
        col = mix(horizon, zenith, pow(dir.y, 0.55));
    } else {
        col = mix(horizon, ground, pow(-dir.y, 0.4));
    }

    // 日盘 + 日晕（HDR 高亮值，是泛光与金属反射的主角）
    float cosSun = dot(dir, sunDir);
    col += vec3(1.0, 0.9, 0.7) * smoothstep(0.9995, 0.99985, cosSun) * 60.0;
    col += vec3(1.0, 0.6, 0.3) * pow(max(cosSun, 0.0), 900.0) * 3.0;

    outColor = vec4(col, 1.0);
}
