#version 330 core

// UI 文本顶点：屏幕空间坐标（正交投影），颜色逐顶点（支持渐变/高亮）
layout (location = 0) in vec2 pos;
layout (location = 1) in vec2 uv;
layout (location = 2) in vec4 color;

uniform mat4 proj;

out vec2 vUv;
out vec4 vColor;

void main() {
    vUv = uv;
    vColor = color;
    gl_Position = proj * vec4(pos, 0.0, 1.0);
}
