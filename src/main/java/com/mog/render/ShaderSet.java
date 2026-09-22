package com.mog.render;

/**
 * 着色器集合：集中持有各渲染管线的着色器，Renderer 按组件组合选择。
 */
public class ShaderSet {

    private final Shader color;        // 顶点色管线
    private final Shader textured;     // 纯纹理管线
    private final Shader lit;          // 经典 Blinn-Phong 光照管线（多光源 + 阴影）
    private final Shader pbr;          // PBR 管线（金属度-粗糙度工作流 + 阴影）
    private final Shader pbrSkinned;   // 蒙皮 PBR 管线（顶点骨骼加权，片元复用 pbr.frag）
    private final Shader shadowDepth;  // 阴影深度遍（光空间，仅写深度）

    public ShaderSet(Shader color, Shader textured, Shader lit, Shader pbr,
                     Shader pbrSkinned, Shader shadowDepth) {
        this.color = color;
        this.textured = textured;
        this.lit = lit;
        this.pbr = pbr;
        this.pbrSkinned = pbrSkinned;
        this.shadowDepth = shadowDepth;
    }

    public Shader getColor() {
        return color;
    }

    public Shader getTextured() {
        return textured;
    }

    public Shader getLit() {
        return lit;
    }

    public Shader getPbr() {
        return pbr;
    }

    public Shader getPbrSkinned() {
        return pbrSkinned;
    }

    public Shader getShadowDepth() {
        return shadowDepth;
    }

    public void cleanup() {
        color.cleanup();
        textured.cleanup();
        lit.cleanup();
        pbr.cleanup();
        pbrSkinned.cleanup();
        shadowDepth.cleanup();
    }
}
