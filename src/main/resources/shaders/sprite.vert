#version 330 core

// 2D 精灵顶点：屏幕空间正交投影（与 ui_text 相同布局，UV 覆盖整张纹理）
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
