#version 330 core

// 阴影深度遍：只需把顶点变换到光空间，深度由固定管线自动写入
layout (location = 0) in vec3 position;

uniform mat4 lightSpaceMatrix;
uniform mat4 model;

void main() {
    gl_Position = lightSpaceMatrix * model * vec4(position, 1.0);
}
