package com.mog.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.glUniformMatrix4fv;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * GLSL 着色器程序封装：编译、链接、uniform 设置、销毁。
 */
public class Shader {

    private static final Logger log = LoggerFactory.getLogger(Shader.class);

    private final int programId;
    private int vertexShaderId;
    private int fragmentShaderId;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    public Shader(String vertexSource, String fragmentSource) {
        programId = glCreateProgram();
        if (programId == 0) {
            throw new IllegalStateException("glCreateProgram 失败");
        }
        try {
            vertexShaderId = createShader(vertexSource, GL_VERTEX_SHADER);
            fragmentShaderId = createShader(fragmentSource, GL_FRAGMENT_SHADER);
            link();
        } catch (RuntimeException e) {
            cleanup();
            throw e;
        }
    }

    /** 从 classpath 加载着色器（路径如 /shaders/default.vert）。 */
    public static Shader loadFromClasspath(String vertPath, String fragPath) {
        return new Shader(readResource(vertPath), readResource(fragPath));
    }

    private static String readResource(String path) {
        try (InputStream in = Shader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败: " + path, e);
        }
    }

    private int createShader(String source, int type) {
        int shaderId = glCreateShader(type);
        if (shaderId == 0) {
            throw new IllegalStateException("创建着色器失败, type=" + type);
        }
        glShaderSource(shaderId, source);
        glCompileShader(shaderId);
        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("着色器编译失败: " + glGetShaderInfoLog(shaderId, 1024)
                    + "\n源码:\n" + source);
        }
        glAttachShader(programId, shaderId);
        return shaderId;
    }

    private void link() {
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("着色器程序链接失败: " + glGetProgramInfoLog(programId, 1024));
        }
        // 链接完成后即可分离/删除单个着色器对象
        if (vertexShaderId != 0) {
            glDetachShader(programId, vertexShaderId);
        }
        if (fragmentShaderId != 0) {
            glDetachShader(programId, fragmentShaderId);
        }
        glValidateProgram(programId);
        if (glGetProgrami(programId, GL_VALIDATE_STATUS) == 0) {
            log.warn("着色器校验未通过: {}", glGetProgramInfoLog(programId, 1024));
        }
    }

    public void bind() {
        glUseProgram(programId);
    }

    public void unbind() {
        glUseProgram(0);
    }

    private int uniformLocation(String name) {
        return uniformCache.computeIfAbsent(name, n -> {
            int loc = glGetUniformLocation(programId, n);
            if (loc < 0) {
                throw new IllegalArgumentException("着色器中不存在 uniform: " + n);
            }
            return loc;
        });
    }

    public void setUniform(String name, int value) {
        glUniform1i(uniformLocation(name), value);
    }

    public void setUniform(String name, float value) {
        glUniform1f(uniformLocation(name), value);
    }

    public void setUniform(String name, float x, float y) {
        glUniform2f(uniformLocation(name), x, y);
    }

    public void setUniform(String name, Vector3f value) {
        glUniform3f(uniformLocation(name), value.x, value.y, value.z);
    }

    public void setUniform(String name, Matrix4f value) {
        try (var stack = stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            value.get(buf);
            glUniformMatrix4fv(uniformLocation(name), false, buf);
        }
    }

    public void cleanup() {
        unbind();
        if (vertexShaderId != 0) {
            glDeleteShader(vertexShaderId);
            vertexShaderId = 0;
        }
        if (fragmentShaderId != 0) {
            glDeleteShader(fragmentShaderId);
            fragmentShaderId = 0;
        }
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }
}
