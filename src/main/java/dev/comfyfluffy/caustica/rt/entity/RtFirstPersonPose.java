package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Computes a renderer-matched, geometry-only offset for the local first-person avatar. */
final class RtFirstPersonPose {
    private static final float AVATAR_SCALE = 0.9375f;
    private static final float MODEL_ROOT_Y = -1.501f;
    private static final float CROUCH_RENDER_Y = -0.125f;
    private static final float CROUCH_HEAD_PIVOT = 4.2f / 16.0f;
    // User-accepted first-person placement. UI/config offsets are adjustments around this origin.
    private static final float BASE_FORWARD = -0.30f;
    private static final float BASE_VERTICAL = -0.25f;

    private RtFirstPersonPose() {
    }

    static boolean active(Minecraft minecraft, Entity entity) {
        return CausticaConfig.Rt.FirstPerson.ENABLED.value()
                && minecraft.options.getCameraType().isFirstPerson()
                && entity instanceof Player
                && entity == minecraft.player
                && entity == minecraft.getCameraEntity();
    }

    static Vec3 offset(Player player, AvatarRenderState state, float partialTick, Vec3 cameraPosition) {
        double px = Mth.lerp(partialTick, player.xo, player.getX());
        double py = Mth.lerp(partialTick, player.yo, player.getY());
        double pz = Mth.lerp(partialTick, player.zo, player.getZ());

        Matrix4f root = new Matrix4f();
        if (state.hasPose(Pose.SLEEPING) && state.bedOrientation != null) {
            float bedOffset = state.eyeHeight - 0.1f;
            root.translate(-state.bedOrientation.getStepX() * bedOffset, 0.0f,
                    -state.bedOrientation.getStepZ() * bedOffset);
        }
        if (state.isCrouching) {
            root.translate(0.0f, CROUCH_RENDER_Y * state.scale, 0.0f);
        }
        root.scale(state.scale);
        applyLivingRotations(root, state);
        if (state.isFallFlying) {
            if (!state.isAutoSpinAttack) {
                float xRot = state.fallFlyingScale() * (-90.0f - state.xRot);
                root.rotateX((float) Math.toRadians(xRot));
            }
            if (state.shouldApplyFlyingYRot) {
                root.rotateY(state.flyingYRot);
            }
        } else if (state.swimAmount > 0.0f) {
            float target = state.isInWater ? -90.0f - state.xRot : -90.0f;
            root.rotateX((float) Math.toRadians(Mth.lerp(state.swimAmount, 0.0f, target)));
            if (state.isVisuallySwimming) {
                root.translate(0.0f, -1.0f, 0.3f);
            }
        }
        root.scale(-AVATAR_SCALE, -AVATAR_SCALE, AVATAR_SCALE);
        root.translate(0.0f, MODEL_ROOT_Y, 0.0f);

        Vector3f head = root.transformPosition(new Vector3f(
                0.0f, state.isCrouching ? CROUCH_HEAD_PIVOT : 0.0f, 0.0f));
        double bodyYaw = Math.toRadians(state.bodyRot);
        double forwardX = -Math.sin(bodyYaw);
        double forwardZ = Math.cos(bodyYaw);
        double rightX = Math.cos(bodyYaw);
        double rightZ = Math.sin(bodyYaw);
        float forward = BASE_FORWARD + CausticaConfig.Rt.FirstPerson.FORWARD_OFFSET.value();
        float vertical = BASE_VERTICAL + CausticaConfig.Rt.FirstPerson.VERTICAL_OFFSET.value();
        float lateral = CausticaConfig.Rt.FirstPerson.LATERAL_OFFSET.value();

        return new Vec3(
                cameraPosition.x - (px + head.x) + forwardX * forward + rightX * lateral,
                cameraPosition.y - (py + head.y) + vertical,
                cameraPosition.z - (pz + head.z) + forwardZ * forward + rightZ * lateral);
    }

    private static void applyLivingRotations(Matrix4f root, AvatarRenderState state) {
        float bodyRot = state.bodyRot;
        if (state.isFullyFrozen) {
            bodyRot += (float) (Math.cos(Mth.floor(state.ageInTicks) * 3.25) * Math.PI * 0.4);
        }
        boolean sleeping = state.hasPose(Pose.SLEEPING);
        if (!sleeping) {
            root.rotateY((float) Math.toRadians(180.0f - bodyRot));
        }
        if (state.deathTime > 0.0f) {
            float progress = Mth.sqrt(Math.min(1.0f, (state.deathTime - 1.0f) / 20.0f * 1.6f));
            root.rotateZ((float) Math.toRadians(progress * 90.0f));
        } else if (state.isAutoSpinAttack) {
            root.rotateX((float) Math.toRadians(-90.0f - state.xRot));
            root.rotateY((float) Math.toRadians(state.ageInTicks * -75.0f));
        } else if (sleeping) {
            root.rotateY((float) Math.toRadians(sleepDirectionRotation(state.bedOrientation, bodyRot)));
            root.rotateZ((float) Math.toRadians(90.0f));
            root.rotateY((float) Math.toRadians(270.0f));
        } else if (state.isUpsideDown) {
            root.translate(0.0f, (state.boundingBoxHeight + 0.1f) / state.scale, 0.0f);
            root.rotateZ((float) Math.toRadians(180.0f));
        }
    }

    private static float sleepDirectionRotation(Direction direction, float fallback) {
        if (direction == null) {
            return fallback;
        }
        return switch (direction) {
            case SOUTH -> 90.0f;
            case WEST -> 0.0f;
            case NORTH -> 270.0f;
            case EAST -> 180.0f;
            default -> 0.0f;
        };
    }
}
