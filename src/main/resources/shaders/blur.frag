#version 330 core

// 泛光第二步：可分离高斯模糊（9 抽头）——horizontal 控制方向，
// 在两个 FBO 间 ping-pong 多次，等效于更大半径的模糊
in vec2 vTexCoord;

uniform sampler2D image;
uniform bool horizontal;
uniform vec2 texelSize;   // 1.0 / 纹理尺寸

out vec4 outColor;

const float weight[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

void main() {
    vec3 result = texture(image, vTexCoord).rgb * weight[0];
    vec2 offset = horizontal ? vec2(texelSize.x, 0.0) : vec2(0.0, texelSize.y);
    for (int i = 1; i < 5; i++) {
        result += texture(image, vTexCoord + offset * float(i)).rgb * weight[i];
        result += texture(image, vTexCoord - offset * float(i)).rgb * weight[i];
    }
    outColor = vec4(result, 1.0);
}
