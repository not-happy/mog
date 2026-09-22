#version 330 core

layout (location = 0) in vec3 position;
layout (location = 2) in vec2 texCoord;
layout (location = 3) in vec3 normal;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform mat4 lightSpaceMatrix;

out vec3 fragWorldPos;
out vec3 fragNormal;
out vec2 fragTexCoord;
out vec4 fragLightSpacePos;

void main() {
    vec4 worldPos = model * vec4(position, 1.0);
    fragWorldPos = worldPos.xyz;
    // 法线变换用 mat3(model)：仅当模型无非均匀缩放时正确（当前引擎约定如此），
    // 严格做法是逆转置矩阵，等引入非均匀缩放时再升级
    fragNormal = normalize(mat3(model) * normal);
    fragTexCoord = texCoord;
    // 光空间位置：阴影比较用
    fragLightSpacePos = lightSpaceMatrix * worldPos;
    gl_Position = projection * view * worldPos;
}
