#version 330 core

// 预滤波环境图：GGX 重要性采样，按粗糙度卷积（每个 mip 一级粗糙度）
// split-sum 近似的第一项：∫ L(ω) D(h) (n·ω) dω / ∫ D(h) (n·ω) dω
in vec3 localPos;

uniform samplerCube environmentMap;
uniform float roughness;   // 0..1，由 mip 级别决定

out vec4 outColor;

// ===== 采样工具（与 ibl_brdf.frag 相同；GLSL 无 include 机制，只能复制）=====
float RadicalInverse_VdC(uint bits) {
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10; // / 0x100000000
}
vec2 Hammersley(int i, int N) {
    return vec2(float(i) / float(N), RadicalInverse_VdC(uint(i)));
}
/** 按 GGX 分布重要性采样半程向量 H */
vec3 ImportanceSampleGGX(vec2 Xi, vec3 N, float roughness) {
    float a = roughness * roughness;
    float phi = 2.0 * 3.14159265 * Xi.x;
    float cosTheta = sqrt((1.0 - Xi.y) / (1.0 + (a * a - 1.0) * Xi.y));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    vec3 H = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
    // 切线空间 -> 世界空间
    vec3 up = abs(N.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(up, N));
    vec3 bitangent = cross(N, tangent);
    return normalize(tangent * H.x + bitangent * H.y + N * H.z);
}

void main() {
    vec3 N = normalize(localPos);
    // 预滤波假设 V = R = N（镜面主方向近似，LearnOpenGL 同款）
    vec3 R = N;
    vec3 V = R;

    const int SAMPLES = 512;
    vec3 prefilteredColor = vec3(0.0);
    float totalWeight = 0.0;

    for (int i = 0; i < SAMPLES; i++) {
        vec2 Xi = Hammersley(i, SAMPLES);
        vec3 H = ImportanceSampleGGX(Xi, N, roughness);
        vec3 L = normalize(2.0 * dot(V, H) * H - V);

        float NdotL = max(dot(N, L), 0.0);
        if (NdotL > 0.0) {
            prefilteredColor += texture(environmentMap, L).rgb * NdotL;
            totalWeight += NdotL;
        }
    }
    prefilteredColor /= max(totalWeight, 1e-4);

    outColor = vec4(prefilteredColor, 1.0);
}
