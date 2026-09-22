#version 330 core

// 粒子顶点：相机朝向公告板（CPU 展开四边形），普通三角形图元——
// 不用 GL_POINTS（点精灵有尺寸上限且部分驱动实现不佳，WARP/d3d12 上会挂起）
layout (location = 0) in vec3 position;
layout (location = 1) in vec2 uv;      // 面片局部坐标 [-1,1]，片元里裁圆
layout (location = 2) in vec4 color;

uniform mat4 view;
uniform mat4 projection;

out vec2 vUv;
out vec4 vColor;

void main() {
    vUv = uv;
    vColor = color;
    gl_Position = projection * view * vec4(position, 1.0);
}
