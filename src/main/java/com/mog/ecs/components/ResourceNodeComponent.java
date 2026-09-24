package com.mog.ecs.components;

import com.mog.ecs.Component;

/**
 * 资源节点组件（C3）：标记实体为地表资源节点的视图。纯数据。
 *
 * nodeId 是 SurfaceSession 的逻辑占位 id（跨 Tab 恒定）——视图系统每帧凭它
 * 从会话查 ResourceNode 真源抄写缩放/位置/枯竭换 Mesh。
 */
public class ResourceNodeComponent implements Component {

    private final int nodeId;

    public ResourceNodeComponent(int nodeId) {
        this.nodeId = nodeId;
    }

    public int getNodeId() {
        return nodeId;
    }
}
