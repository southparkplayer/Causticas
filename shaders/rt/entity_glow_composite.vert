#version 460

// Fullscreen triangle, no vertex buffer (the classic gl_VertexIndex trick: 3 vertices covering the whole
// clip-space square, the excess clipped by the viewport). Used to run entity_glow_composite.frag once per
// pixel over the main render target.
void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
