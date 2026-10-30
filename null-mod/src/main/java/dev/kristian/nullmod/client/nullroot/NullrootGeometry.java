package dev.kristian.nullmod.client.nullroot;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * The frame a NULLROOT model is drawn in: its local +Y is the normal of the face it grows out of
 * and +Z is its "front". Converts world directions into that frame (to know where to turn) and
 * turns a pose stack into it. Render thread only; reuses one quaternion.
 */
final class NullrootGeometry {
    private static final Quaternionf ROTATION = new Quaternionf();

    private NullrootGeometry() {
    }

    /** Rotates the pose stack so +Y points out of the given face. */
    static void rotateToFace(PoseStack poseStack, Direction face) {
        switch (face) {
            case DOWN -> poseStack.mulPose(ROTATION.rotationX(Mth.PI));
            case NORTH -> poseStack.mulPose(ROTATION.rotationX(-Mth.HALF_PI));
            case SOUTH -> poseStack.mulPose(ROTATION.rotationX(Mth.HALF_PI));
            case EAST -> poseStack.mulPose(ROTATION.rotationZ(-Mth.HALF_PI));
            case WEST -> poseStack.mulPose(ROTATION.rotationZ(Mth.HALF_PI));
            default -> {
            }
        }
    }

    static void rotateX(PoseStack poseStack, float radians) {
        if (radians != 0.0F) {
            poseStack.mulPose(ROTATION.rotationX(radians));
        }
    }

    static void rotateY(PoseStack poseStack, float radians) {
        if (radians != 0.0F) {
            poseStack.mulPose(ROTATION.rotationY(radians));
        }
    }

    static void rotateZ(PoseStack poseStack, float radians) {
        if (radians != 0.0F) {
            poseStack.mulPose(ROTATION.rotationZ(radians));
        }
    }

    /**
     * The turn (radians about the local up axis) that makes the model's front face a world
     * direction; the inverse of {@link #rotateToFace} applied to (dx, dy, dz).
     */
    static float yawToward(Direction face, double dx, double dy, double dz) {
        double lx;
        double lz;
        switch (face) {
            case DOWN -> {
                lx = dx;
                lz = -dz;
            }
            case NORTH -> {
                lx = dx;
                lz = dy;
            }
            case SOUTH -> {
                lx = dx;
                lz = -dy;
            }
            case EAST -> {
                lx = -dy;
                lz = dz;
            }
            case WEST -> {
                lx = dy;
                lz = dz;
            }
            default -> {
                lx = dx;
                lz = dz;
            }
        }
        if (lx * lx + lz * lz < 1.0E-8) {
            return Float.NaN;
        }
        return (float) Math.atan2(lx, lz);
    }

    /** Moves an angle toward a target by at most {@code step}, the short way around (radians). */
    static float approachAngle(float current, float target, float step) {
        float delta = wrap(target - current);
        if (Math.abs(delta) <= step) {
            return target;
        }
        return wrap(current + Math.copySign(step, delta));
    }

    static float wrap(float radians) {
        float r = radians % (Mth.PI * 2.0F);
        if (r > Mth.PI) {
            r -= Mth.PI * 2.0F;
        } else if (r < -Mth.PI) {
            r += Mth.PI * 2.0F;
        }
        return r;
    }

    static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    static long mix(long h) {
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
