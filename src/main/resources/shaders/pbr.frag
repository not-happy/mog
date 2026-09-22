#version 330 core

// PBR 片元阶段：glTF 金属度-粗糙度工作流（Cook-Torrance BRDF）。
// 直接光：1 方向光（带阴影） + 最多 4 点光源；IBL（环境贴图）不在本阶段范围。
// 计算全程在线性空间：sRGB 纹理先线性化，输出前 Reinhard 色调映射 + gamma 编码。
#define MAX_POINT_LIGHTS 4

in vec3 fragWorldPos;
in vec3 fragNormal;
in vec2 fragTexCoord;
in vec4 fragLightSpacePos;

// 方向光
uniform vec3 lightDir;
uniform vec3 lightColor;
uniform float lightIntensity;

// 点光源
struct PointLight {
    vec3 position;
    vec3 color;
    float intensity;
    float constant;
    float linear;
    float quadratic;
};
uniform int pointLightCount;
uniform PointLight pointLights[MAX_POINT_LIGHTS];

// 相机
uniform vec3 viewPos;

// PBR 材质
uniform bool useTexture;
uniform sampler2D texSampler;   // 基色纹理（sRGB）
uniform vec3 albedo;
uniform float metallic;
uniform float roughness;

// IBL 环境光照（split-sum）：辐照度图 / 预滤波环境图 / BRDF 积分 LUT
uniform samplerCube irradianceMap;
uniform samplerCube prefilteredMap;
uniform sampler2D brdfLUT;

// 阴影深度图（纹理单元 1）
uniform sampler2D shadowMap;

out vec4 outColor;

const float PI = 3.14159265359;
// 预滤波环境图的 mip 级数上限（与 Environment.PREFILTER_MIPS-1 一致）
const float MAX_REFLECTION_LOD = 4.0;

// ===== Cook-Torrance 三项 =====

/** 法线分布函数 D：GGX/Trowbridge-Reitz —— 决定高光斑的形状与大小 */
float DistributionGGX(vec3 N, vec3 H, float a) {
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

/** 几何函数 G 的单边项：Schlick-GGX 近似 */
float GeometrySchlickGGX(float NdotV, float k) {
    return NdotV / (NdotV * (1.0 - k) + k);
}

/** 几何函数 G：Smith 方法（视线 + 光线两侧遮蔽），直接光 k = a²/2 */
float GeometrySmith(vec3 N, vec3 V, vec3 L, float k) {
    return GeometrySchlickGGX(max(dot(N, V), 0.0), k)
         * GeometrySchlickGGX(max(dot(N, L), 0.0), k);
}

/** 菲涅尔 F：Schlick 近似（cosθ 为半程向量与视线夹角） */
vec3 FresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

/** 菲涅尔（带粗糙度）：环境 IBL 用——掠射角处粗糙表面不该出现全反射 */
vec3 FresnelSchlickRoughness(float cosTheta, vec3 F0, float rough) {
    return F0 + (max(vec3(1.0 - rough), F0) - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// ===== 阴影（与 lit.frag 相同的 PCF 实现）=====
float calcShadowFactor(vec3 n, vec3 l) {
    vec3 proj = fragLightSpacePos.xyz / fragLightSpacePos.w;
    proj = proj * 0.5 + 0.5;
    float currentDepth = proj.z;
    if (currentDepth > 1.0) {
        return 1.0;
    }
    float bias = max(0.02 * (1.0 - dot(n, l)), 0.0015);
    float shadow = 0.0;
    vec2 texelSize = 1.0 / vec2(textureSize(shadowMap, 0));
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float closestDepth = texture(shadowMap, proj.xy + vec2(x, y) * texelSize).r;
            shadow += (currentDepth - bias > closestDepth) ? 1.0 : 0.0;
        }
    }
    return 1.0 - shadow / 9.0;
}

/** 单个直接光源的 Cook-Torrance 贡献（radiance 已含强度与衰减） */
vec3 pbrDirectLight(vec3 N, vec3 V, vec3 L, vec3 radiance, vec3 F0, vec3 alb, float a, float k) {
    vec3 H = normalize(V + L);
    float NDF = DistributionGGX(N, H, a);
    float G = GeometrySmith(N, V, L, k);
    vec3 F = FresnelSchlick(max(dot(H, V), 0.0), F0);

    // 镜面项（分母防除零）
    vec3 specular = NDF * G * F / max(4.0 * dot(N, V) * dot(N, L), 0.001);
    // 漫反射项：能量守恒——镜面反射占比 F 的部分不再参与漫反射；金属无漫反射
    vec3 kD = (1.0 - F) * (1.0 - metallic);
    float NdotL = max(dot(N, L), 0.0);
    return (kD * alb / PI + specular) * radiance * NdotL;
}

void main() {
    // 基色纹理是 sRGB 编码，先线性化再参与光照计算
    vec3 texColor = useTexture ? pow(texture(texSampler, fragTexCoord).rgb, vec3(2.2)) : vec3(1.0);
    vec3 alb = texColor * albedo;

    vec3 N = normalize(fragNormal);
    vec3 V = normalize(viewPos - fragWorldPos);

    // Disney 惯例：感知粗糙度的平方作为 BRDF 的 alpha；直接光 k = a²/2
    float a = max(roughness * roughness, 0.001);
    float k = a * 0.5;
    // 电介质 F0≈0.04，金属用 albedo 作为 F0
    vec3 F0 = mix(vec3(0.04), alb, metallic);

    vec3 Lo = vec3(0.0);

    // 方向光 × 阴影
    vec3 L = normalize(-lightDir);
    Lo += pbrDirectLight(N, V, L, lightColor * lightIntensity, F0, alb, a, k)
          * calcShadowFactor(N, L);

    // 点光源（三项式衰减，暂无阴影）
    for (int i = 0; i < pointLightCount && i < MAX_POINT_LIGHTS; i++) {
        PointLight pl = pointLights[i];
        vec3 toLight = pl.position - fragWorldPos;
        float dist = length(toLight);
        float atten = 1.0 / (pl.constant + pl.linear * dist + pl.quadratic * dist * dist);
        vec3 radiance = pl.color * pl.intensity * atten;
        Lo += pbrDirectLight(N, V, toLight / dist, radiance, F0, alb, a, k);
    }

    // ===== 环境光：IBL split-sum =====
    // 漫反射项：辐照度图（余弦卷积环境光）× albedo
    vec3 Fenv = FresnelSchlickRoughness(max(dot(N, V), 0.0), F0, roughness);
    vec3 kD = (1.0 - Fenv) * (1.0 - metallic);
    vec3 irradiance = texture(irradianceMap, N).rgb;
    vec3 diffuseIBL = irradiance * alb;
    // 镜面项：预滤波环境图（按粗糙度选 mip）× (F·scale + bias)（BRDF LUT）
    vec3 R = reflect(-V, N);
    vec3 prefilteredColor = textureLod(prefilteredMap, R, roughness * MAX_REFLECTION_LOD).rgb;
    vec2 envBRDF = texture(brdfLUT, vec2(max(dot(N, V), 0.0), 1.0 - roughness)).xy;
    vec3 specularIBL = prefilteredColor * (Fenv * envBRDF.x + envBRDF.y);

    vec3 color = kD * diffuseIBL + specularIBL + Lo;

    // HDR 管线：输出线性 HDR（可 >1.0），色调映射与 gamma 编码统一在后处理合成遍完成
    outColor = vec4(color, 1.0);
}
