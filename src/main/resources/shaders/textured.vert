#version 330 core

layout (location = 0) in vec3 position;
layout (location = 2) in vec2 texCoord;

uniform mat4 modelView;
uniform mat4 projection;

out vec2 fragTexCoord;

void main() {
    gl_Position = projection * modelView * vec4(position, 1.0);
    fragTexCoord = texCoord;
}
