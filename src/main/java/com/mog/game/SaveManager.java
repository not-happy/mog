package com.mog.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mog.ecs.World;
import com.mog.ecs.components.OrbitComponent;
import com.mog.ecs.components.TransformComponent;
import com.mog.render.Camera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存档管理器（v1）：序列化世界的"动态状态"——每个实体的 Transform、轨道角、相机位姿。
 *
 * 边界说明：Mesh/Material/Texture 等静态定义不入库（由场景构建器确定性重建），
 * 存档只恢复"玩到哪了"。这是静态关卡数据与动态存档分离的常见工程实践。
 * 按实体名匹配恢复；重名实体后写覆盖（当前场景实体名唯一）。
 */
public class SaveManager {

    private static final Logger log = LoggerFactory.getLogger(SaveManager.class);
    private static final Path SAVE_PATH = Path.of("saves/game.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ===== JSON DTO（纯数据）=====

    public static class EntityState {
        public String name;
        public float[] position = new float[3];
        public float[] rotation = new float[3];
        public float[] scale = new float[3];
        /** 轨道角（无 OrbitComponent 的实体为 null） */
        public Float orbitAngle;
    }

    public static class SaveData {
        public String version = "1";
        public float[] cameraPos = new float[3];
        public float[] cameraRot = new float[3];
        public List<EntityState> entities = new ArrayList<>();
    }

    // ===== 存 / 读 =====

    public void save(World world, Camera camera) {
        SaveData data = new SaveData();
        var pos = camera.getPosition();
        var rot = camera.getRotation();
        data.cameraPos = new float[]{pos.x, pos.y, pos.z};
        data.cameraRot = new float[]{rot.x, rot.y, rot.z};

        for (int e : world.view(TransformComponent.class)) {
            TransformComponent t = world.getComponent(e, TransformComponent.class);
            EntityState s = new EntityState();
            s.name = world.getName(e);
            s.position = new float[]{t.getPosition().x, t.getPosition().y, t.getPosition().z};
            s.rotation = new float[]{t.getRotation().x, t.getRotation().y, t.getRotation().z};
            s.scale = new float[]{t.getScale().x, t.getScale().y, t.getScale().z};
            OrbitComponent o = world.getComponent(e, OrbitComponent.class);
            if (o != null) {
                s.orbitAngle = o.getAngle();
            }
            data.entities.add(s);
        }

        try {
            Files.createDirectories(SAVE_PATH.getParent());
            Files.writeString(SAVE_PATH, GSON.toJson(data), StandardCharsets.UTF_8);
            log.info("存档成功: {} ({} 实体)", SAVE_PATH.toAbsolutePath(), data.entities.size());
        } catch (IOException ex) {
            log.error("存档失败", ex);
        }
    }

    public void load(World world, Camera camera) {
        if (!Files.exists(SAVE_PATH)) {
            log.warn("存档不存在: {}", SAVE_PATH.toAbsolutePath());
            return;
        }
        SaveData data;
        try {
            data = GSON.fromJson(Files.readString(SAVE_PATH, StandardCharsets.UTF_8), SaveData.class);
        } catch (Exception ex) {
            log.error("存档解析失败", ex);
            return;
        }

        camera.getPosition().set(data.cameraPos[0], data.cameraPos[1], data.cameraPos[2]);
        camera.getRotation().set(data.cameraRot[0], data.cameraRot[1], data.cameraRot[2]);

        // 名字 -> 实体ID 索引
        Map<String, Integer> byName = new HashMap<>();
        for (int e : world.view(TransformComponent.class)) {
            byName.put(world.getName(e), e);
        }

        int applied = 0;
        int missing = 0;
        for (EntityState s : data.entities) {
            Integer e = byName.get(s.name);
            if (e == null) {
                missing++;
                continue;
            }
            TransformComponent t = world.getComponent(e, TransformComponent.class);
            t.getPosition().set(s.position[0], s.position[1], s.position[2]);
            t.getRotation().set(s.rotation[0], s.rotation[1], s.rotation[2]);
            t.getScale().set(s.scale[0], s.scale[1], s.scale[2]);
            if (s.orbitAngle != null) {
                OrbitComponent o = world.getComponent(e, OrbitComponent.class);
                if (o != null) {
                    o.setAngle(s.orbitAngle);
                }
            }
            applied++;
        }
        log.info("读档成功: 恢复 {} 实体{} (相机已复位)", applied,
                missing > 0 ? "，" + missing + " 个存档实体在当前世界不存在" : "");
    }
}
