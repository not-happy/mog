#version 330 core

// 立方体面捕获通用顶点着色器：天空/辐照度/预滤波三遍共用
layout (location = 0) in vec3 position;

uniform mat4 view;
uniform mat4 projection;

out vec3 localPos;

void main() {
    localPos = position;
    gl_Position = projection * view * vec4(position, 1.0);
}
