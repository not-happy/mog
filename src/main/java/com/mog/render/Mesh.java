package com.mog.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 网格封装：VAO/VBO/EBO 的创建、绘制与销毁。
 * 顶点属性约定：
 *   location 0 = position  (vec3)
 *   location 1 = color     (vec3)
 *   location 2 = texCoord  (vec2)
 *   location 3 = normal    (vec3)
 *   location 4 = boneIds   (ivec4, 蒙皮)
 *   location 5 = weights   (vec4,  蒙皮)
 */
public class Mesh {

    private final int vaoId;
    private final List<Integer> vboIds = new ArrayList<>();
    private final int eboId;
    private final int vertexCount;
    private final boolean indexed;

    /**
     * 完整构造（含蒙皮属性）。
     *
     * @param positions 顶点位置，每 3 个 float 一个顶点（必填）
     * @param colors    顶点颜色（可为 null）
     * @param texCoords 纹理坐标，每 2 个 float（可为 null）
     * @param normals   法线，每 3 个 float（可为 null）
     * @param boneIds   骨骼索引，每 4 个 int（可为 null，非蒙皮网格）
     * @param weights   骨骼权重，每 4 个 float（可为 null）
     * @param indices   索引（可为 null，表示非索引绘制）
     */
    public Mesh(float[] positions, float[] colors, float[] texCoords, float[] normals,
                int[] boneIds, float[] weights, int[] indices) {
        IntBuffer ib = null;
        try {
            vaoId = glGenVertexArrays();
            glBindVertexArray(vaoId);

            uploadAttribute(positions, 0, 3);
            uploadAttribute(colors, 1, 3);
            uploadAttribute(texCoords, 2, 2);
            uploadAttribute(normals, 3, 3);
            uploadIntAttribute(boneIds, 4, 4);
            uploadAttribute(weights, 5, 4);

            // 索引缓冲
            if (indices != null) {
                ib = memAllocInt(indices.length).put(indices);
                ib.flip();
                eboId = glGenBuffers();
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
                vertexCount = indices.length;
                indexed = true;
            } else {
                eboId = 0;
                vertexCount = positions.length / 3;
                indexed = false;
            }

            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            if (ib != null) {
                memFree(ib);
            }
        }
    }

    /** 兼容构造：无蒙皮属性。 */
    public Mesh(float[] positions, float[] colors, float[] texCoords, float[] normals, int[] indices) {
        this(positions, colors, texCoords, normals, null, null, indices);
    }

    /** 兼容旧构造：无 normals。 */
    public Mesh(float[] positions, float[] colors, float[] texCoords, int[] indices) {
        this(positions, colors, texCoords, null, indices);
    }

    /** 上传一个 float 顶点属性到指定 location；data 为 null 时跳过。 */
    private void uploadAttribute(float[] data, int location, int components) {
        if (data == null) {
            return;
        }
        FloatBuffer buf = memAllocFloat(data.length).put(data);
        buf.flip();
        try {
            int vbo = glGenBuffers();
            vboIds.add(vbo);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
            glEnableVertexAttribArray(location);
            glVertexAttribPointer(location, components, GL_FLOAT, false, 0, 0);
        } finally {
            memFree(buf);
        }
    }

    /** 上传一个 int 顶点属性（骨骼索引用 GL_INT，不做归一化）。 */
    private void uploadIntAttribute(int[] data, int location, int components) {
        if (data == null) {
            return;
        }
        IntBuffer buf = memAllocInt(data.length).put(data);
        buf.flip();
        try {
            int vbo = glGenBuffers();
            vboIds.add(vbo);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
            glEnableVertexAttribArray(location);
            glVertexAttribIPointer(location, components, GL_INT, 0, 0);
        } finally {
            memFree(buf);
        }
    }

    /** 便捷构造：仅位置 + 颜色的非索引网格。 */
    public static Mesh colored(float[] positions, float[] colors) {
        return new Mesh(positions, colors, null, null, null);
    }

    /** 便捷构造：位置 + 颜色 + 索引。 */
    public static Mesh coloredIndexed(float[] positions, float[] colors, int[] indices) {
        return new Mesh(positions, colors, null, null, indices);
    }

    /** 便捷构造：位置 + 纹理坐标 + 索引。 */
    public static Mesh textured(float[] positions, float[] texCoords, int[] indices) {
        return new Mesh(positions, null, texCoords, null, indices);
    }

    public void render() {
        glBindVertexArray(vaoId);
        if (indexed) {
            glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0);
        } else {
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        }
        glBindVertexArray(0);
    }

    public void cleanup() {
        for (int vbo : vboIds) {
            glDeleteBuffers(vbo);
        }
        vboIds.clear();
        if (eboId != 0) {
            glDeleteBuffers(eboId);
        }
        glDeleteVertexArrays(vaoId);
    }

    public int getVertexCount() {
        return vertexCount;
    }
}
