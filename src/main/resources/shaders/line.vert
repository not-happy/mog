#version 330 core

// 轨道残影线：无光照顶点色（含 alpha 渐隐），view/projection 由 Renderer 同款矩阵传入
layout (location = 0) in vec3 position;
layout (location = 1) in vec4 color;

uniform mat4 view;
uniform mat4 projection;

out vec4 vColor;

void main() {
    vColor = color;
    gl_Position = projection * view * vec4(position, 1.0);
}
