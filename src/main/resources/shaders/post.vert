#version 330 core

// 后处理通用顶点着色器：全屏 quad（NDC 坐标直出，无需任何矩阵）
layout (location = 0) in vec3 position;
layout (location = 2) in vec2 texCoord;

out vec2 vTexCoord;

void main() {
    vTexCoord = texCoord;
    gl_Position = vec4(position.xy, 0.0, 1.0);
}
