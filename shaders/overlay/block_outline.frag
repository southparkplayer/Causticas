#version 460
#extension GL_EXT_ray_query : require

// Per-fragment occlusion for the targeted block's wireframe edges: an inline rayQueryEXT test against the
// same TLAS the primary trace uses, from the camera to this fragment's world position. Occluded pixels
// (anything opaque in front of the edge, including the block's own near faces) are discarded — no depth
// buffer involved, since RT's own gDepth is at DLSS-RR's internal render resolution, not this pass's
// full display resolution (see RtWorldOverlay).

layout(push_constant) uniform Push {
    mat4 curViewProj; // 0, 64B (unused here; kept so both stages share one push range)
    vec3 camOffset;   // 64, padded to 16B — camera's position in the terrain's rebase space
    vec4 color;       // 80, 16B
} pc;

layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;

layout(location = 0) in vec3 vCamRel; // this fragment's position relative to the camera

layout(location = 0) out vec4 outColor;

// Same primary-camera-ray cull mask world.rgen's tracePath uses for bounce 0 (see CULL_PRIMARY there /
// RtEntities.MASK_PRIMARY): the local first-person body is visible here just as it is to the world camera ray;
// the separately captured head remains secondary-only to avoid camera-origin self-intersection. This ray
// originates at the camera like a primary ray, so it must use the SAME mask. Using 0xFF would also include
// the secondary-only head and could immediately self-intersect at the camera origin.
const uint CULL_PRIMARY = 0x02u;

void main() {
    float dist = length(vCamRel);
    if (dist < 1.0e-4) {
        outColor = pc.color;
        return;
    }
    vec3 dir = vCamRel / dist;
    float tMax = max(dist - 0.01, 0.001);

    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT,
            CULL_PRIMARY, pc.camOffset, 0.001, dir, tMax);
    while (rayQueryProceedEXT(rq)) {
    }
    if (rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT) {
        discard;
    }
    outColor = pc.color;
}
