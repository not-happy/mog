#version 330 core

in vec2 fragTexCoord;

uniform sampler2D texSampler;

out vec4 outColor;

void main() {
    // sRGB 纹理线性化后输出（HDR 管线：显示端 gamma 由后处理合成遍统一完成）
    outColor = vec4(pow(texture(texSampler, fragTexCoord).rgb, vec3(2.2)), 1.0);
}
