package com.mog.ecs.components;

import com.mog.ecs.Component;
import com.mog.render.Texture;

/**
 * 纹理组件：可选；与材质组件叠加时纹理×材质色。纯数据。
 */
public class TextureComponent implements Component {

    private final Texture texture;

    public TextureComponent(Texture texture) {
        this.texture = texture;
    }

    public Texture getTexture() {
        return texture;
    }
}
