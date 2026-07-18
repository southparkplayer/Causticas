// Minimal miss record for reflection/refraction guide probes. These rays consume only hit-vs-miss state;
// sky radiance is supplied by fixed guide values in raygen, so the atmospheric integration is unnecessary.
import world_common;

[shader("miss")]
void main(inout Payload payload) {
    payload.hitT = -1.0;
    payload.normal = float3(0.0, 0.0, 0.0);
    payload.geometricNormal = float3(0.0, 0.0, 0.0);
    payload.flags = 0u;
}
