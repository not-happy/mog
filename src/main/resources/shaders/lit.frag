#version 330 core

// 前向渲染多光源 + 方向光阴影：1 个方向光（带阴影） + 最多 4 个点光源
// （点光源阴影需要立方体深度图，属进阶内容，暂不实现）
#define MAX_POINT_LIGHTS 4

in vec3 fragWorldPos;
in vec3 fragNormal;
in vec2 fragTexCoord;
in vec4 fragLightSpacePos;

// 方向光（世界空间）
uniform vec3 lightDir;        // 光的传播方向
uniform vec3 lightColor;
uniform float lightIntensity;

// 点光源数组
struct PointLight {
    vec3 position;
    vec3 color;
    float intensity;
    float constant;    // 衰减三项式系数
    float linear;
    float quadratic;
};
uniform int pointLightCount;
uniform PointLight pointLights[MAX_POINT_LIGHTS];

// 相机
uniform vec3 viewPos;

// 材质
uniform bool useTexture;
uniform sampler2D texSampler;
uniform vec3 materialColor;
uniform float specularPower;    // 高光指数
uniform float specularStrength; // 高光强度

// 阴影深度图（绑定在纹理单元 1）
uniform sampler2D shadowMap;

out vec4 outColor;

// 场景级环境光常数（与方向光强度解耦，避免压暗主光时全场景死黑）
const float AMBIENT = 0.15;

/** 单个光源的 Blinn-Phong 贡献：l 为指向光源的单位向量，atten 为距离衰减 */
vec3 shade(vec3 baseColor, vec3 n, vec3 v, vec3 l, vec3 lColor, float intensity, float atten) {
    // 漫反射：Lambert 定律
    float diff = max(dot(n, l), 0.0);
    vec3 diffuse = diff * lColor * intensity * atten * baseColor;
    // 镜面高光：Blinn-Phong 半程向量
    vec3 h = normalize(l + v);
    float spec = pow(max(dot(n, h), 0.0), specularPower) * specularStrength;
    vec3 specular = spec * lColor * intensity * atten;
    return diffuse + specular;
}

/**
 * 方向光阴影系数：1.0 = 完全受光，0.0 = 完全阴影。
 * 把片元的光空间位置做透视除法 + [0,1] 映射后采样深度图比较；
 * 3x3 PCF（百分比近似过滤）软化阴影边缘；
 * 斜坡自适应偏差防治"阴影痤疮"（自遮挡条纹）。
 */
float calcShadowFactor(vec3 n, vec3 l) {
    vec3 proj = fragLightSpacePos.xyz / fragLightSpacePos.w;
    proj = proj * 0.5 + 0.5;          // [-1,1] -> [0,1]（xy 采样坐标，z 比较深度）

    float currentDepth = proj.z;
    if (currentDepth > 1.0) {
        return 1.0;                    // 超出光源视锥远平面：不参与阴影
    }

    // 偏差随表面与光线夹角增大：斜面处深度梯度大，需要更大偏差
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

void main() {
    // 基色：纹理采样（sRGB -> 线性）× 材质色，或纯材质色。
    // HDR 管线下光照计算必须在线性空间，输出由后处理合成遍统一 tonemap+gamma
    vec3 baseColor = useTexture
        ? pow(texture(texSampler, fragTexCoord).rgb, vec3(2.2)) * materialColor
        : materialColor;

    vec3 n = normalize(fragNormal);
    vec3 v = normalize(viewPos - fragWorldPos);

    // 环境光（不受阴影影响：环境光本来就是间接光近似）
    vec3 result = AMBIENT * baseColor;

    // 方向光 × 阴影系数
    vec3 lDir = normalize(-lightDir);
    result += shade(baseColor, n, v, lDir, lightColor, lightIntensity, 1.0)
              * calcShadowFactor(n, lDir);

    // 点光源（三项式距离衰减，暂无阴影）
    for (int i = 0; i < pointLightCount && i < MAX_POINT_LIGHTS; i++) {
        PointLight pl = pointLights[i];
        vec3 toLight = pl.position - fragWorldPos;
        float dist = length(toLight);
        float atten = 1.0 / (pl.constant + pl.linear * dist + pl.quadratic * dist * dist);
        result += shade(baseColor, n, v, toLight / dist, pl.color, pl.intensity, atten);
    }

    outColor = vec4(result, 1.0);
}
