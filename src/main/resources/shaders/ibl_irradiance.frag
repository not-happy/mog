#version 330 core

// 辐照度图：对环境贴图做余弦加权半球卷积 → 漫反射 IBL
// irradiance(N) = (1/π) ∮ L(ω) (N·ω) dω 的蒙特卡洛近似
in vec3 localPos;

uniform samplerCube environmentMap;

out vec4 outColor;

void main() {
    vec3 N = normalize(localPos);

    // 以 N 为法线构建切线空间基
    vec3 up = abs(N.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 right = normalize(cross(up, N));
    up = normalize(cross(N, right));

    vec3 irradiance = vec3(0.0);
    float sampleDelta = 0.08;   // 弧度步长（低频信号，无需高精度）
    float nrSamples = 0.0;
    for (float phi = 0.0; phi < 2.0 * 3.14159265; phi += sampleDelta) {
        for (float theta = 0.0; theta < 0.5 * 3.14159265; theta += sampleDelta) {
            // 球面 -> 笛卡尔（切线空间），再旋到世界空间
            vec3 tangent = vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta));
            vec3 sampleVec = tangent.x * right + tangent.y * up + tangent.z * N;
            irradiance += texture(environmentMap, sampleVec).rgb * cos(theta) * sin(theta);
            nrSamples += 1.0;
        }
    }
    irradiance = 3.14159265 * irradiance / nrSamples;

    outColor = vec4(irradiance, 1.0);
}
