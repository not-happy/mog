#version 330 core

// 蒙皮 PBR 顶点阶段：骨骼加权变换位置与法线，其余与 pbr.vert 一致。
// 片元阶段直接复用 pbr.frag（输出接口相同）。
#define MAX_BONES 96

layout (location = 0) in vec3 position;
layout (location = 2) in vec2 texCoord;
layout (location = 3) in vec3 normal;
layout (location = 4) in ivec4 boneIds;
layout (location = 5) in vec4 boneWeights;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform mat4 lightSpaceMatrix;
uniform mat4 bones[MAX_BONES];

out vec3 fragWorldPos;
out vec3 fragNormal;
out vec2 fragTexCoord;
out vec4 fragLightSpacePos;

void main() {
    // 线性混合蒙皮（LBS）：四骨骼加权
    mat4 skin = boneWeights.x * bones[boneIds.x]
              + boneWeights.y * bones[boneIds.y]
              + boneWeights.z * bones[boneIds.z]
              + boneWeights.w * bones[boneIds.w];

    vec4 worldPos = model * skin * vec4(position, 1.0);
    fragWorldPos = worldPos.xyz;
    // 法线也要蒙皮（近似：忽略非均匀缩放的逆转置）
    fragNormal = normalize(mat3(model) * (mat3(skin) * normal));
    fragTexCoord = texCoord;
    fragLightSpacePos = lightSpaceMatrix * worldPos;
    gl_Position = projection * view * worldPos;
}
