package dev.kristian.nullmod.client.nullroot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Tendrils built at draw time: a chain of thin boxes (the unit segment of
 * {@link NullrootModels#SEGMENT}, scaled and turned) along a curve from one point to another,
 * and coils of them wound around a captured player. All coordinates are relative to the pose
 * stack's current origin. Point buffers are reused; nothing is allocated per frame.
 */
final class NullrootTendrils {
    static final int MAX_SEGMENTS = 28;
    private static final double[] PX = new double[MAX_SEGMENTS + 1];
    private static final double[] PY = new double[MAX_SEGMENTS + 1];
    private static final double[] PZ = new double[MAX_SEGMENTS + 1];

    private NullrootTendrils() {
    }

    /**
     * A tendril from {@code s} to {@code e} that first leaves along {@code n} (the direction it
     * grows out of), sways sideways by up to {@code wobble} blocks, and thins from
     * {@code thick0} to {@code thick1}.
     */
    static void chain(PoseStack poseStack, VertexConsumer consumer, ModelPart unit,
                      double sx, double sy, double sz, double nx, double ny, double nz, double ex, double ey, double ez,
                      float thick0, float thick1, float wobble, float time, long seed, int light, int overlay, int color) {
        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.02) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        double uz = dz / length;
        // Control points: out along the normal first, then in toward the end.
        double c1x = sx + nx * length * 0.4;
        double c1y = sy + ny * length * 0.4;
        double c1z = sz + nz * length * 0.4;
        double c2x = ex - ux * length * 0.3 + nx * length * 0.12;
        double c2y = ey - uy * length * 0.3 + ny * length * 0.12;
        double c2z = ez - uz * length * 0.3 + nz * length * 0.12;
        // Two directions across the tendril for the sway.
        double ax;
        double ay;
        double az;
        if (Math.abs(uy) < 0.9) {
            ax = -uz;
            ay = 0.0;
            az = ux;
        } else {
            ax = 0.0;
            ay = uz;
            az = -uy;
        }
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= al;
        ay /= al;
        az /= al;
        double bx = uy * az - uz * ay;
        double by = uz * ax - ux * az;
        double bz = ux * ay - uy * ax;

        int n = Mth.clamp((int) Math.ceil(length * 3.5), 3, MAX_SEGMENTS);
        float phase = (seed & 1023L) / 163.0F;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float it = 1.0F - t;
            double b0 = it * it * it;
            double b1 = 3.0 * it * it * t;
            double b2 = 3.0 * it * t * t;
            double b3 = t * t * t;
            double x = b0 * sx + b1 * c1x + b2 * c2x + b3 * ex;
            double y = b0 * sy + b1 * c1y + b2 * c2y + b3 * ey;
            double z = b0 * sz + b1 * c1z + b2 * c2z + b3 * ez;
            float bend = Mth.sin((float) Math.PI * t) * wobble;
            float wa = Mth.sin(time * 0.21F + t * 6.3F + phase) * bend;
            float wb = Mth.cos(time * 0.17F + t * 5.1F + phase * 1.7F) * bend;
            PX[i] = x + ax * wa + bx * wb;
            PY[i] = y + ay * wa + by * wb;
            PZ[i] = z + az * wa + bz * wb;
        }
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5F) / n;
            segment(poseStack, consumer, unit, PX[i], PY[i], PZ[i], PX[i + 1], PY[i + 1], PZ[i + 1],
                    Mth.lerp(t, thick0, thick1), light, overlay, color);
        }
    }

    /**
     * Strands wound around a body standing at (cx, cy, cz): from the feet up to
     * {@code top} blocks, tightening as they climb.
     */
    static void coils(PoseStack poseStack, VertexConsumer consumer, ModelPart unit, double cx, double cy, double cz,
                      float radius, float top, float time, long seed, int light, int overlay, int color) {
        float bottom = 0.06F;
        if (top <= bottom + 0.05F) {
            return;
        }
        float phaseBase = (seed & 255L) / 40.0F;
        for (int strand = 0; strand < 3; strand++) {
            float phase = phaseBase + strand * (float) (Math.PI * 2.0 / 3.0);
            float turns = 1.7F + strand * 0.25F;
            int n = Mth.clamp((int) Math.ceil((top - bottom) * 10.0F) + 2, 3, MAX_SEGMENTS);
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                float y = bottom + (top - bottom) * t;
                float angle = phase + y * (float) (Math.PI * 2.0) * turns + time * 0.03F;
                float r = radius * (1.0F + 0.12F * Mth.sin(y * 9.0F + time * 0.2F + phase)) * (1.0F - 0.15F * t);
                PX[i] = cx + Mth.cos(angle) * r;
                PY[i] = cy + y;
                PZ[i] = cz + Mth.sin(angle) * r;
            }
            for (int i = 0; i < n; i++) {
                float t = (i + 0.5F) / n;
                segment(poseStack, consumer, unit, PX[i], PY[i], PZ[i], PX[i + 1], PY[i + 1], PZ[i + 1],
                        Mth.lerp(t, 0.085F, 0.045F), light, overlay, color);
            }
        }
    }

    /** One box from (x0, y0, z0) to (x1, y1, z1), {@code thickness} blocks across. */
    static void segment(PoseStack poseStack, VertexConsumer consumer, ModelPart unit, double x0, double y0, double z0,
                        double x1, double y1, double z1, float thickness, int light, int overlay, int color) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x0, y0, z0);
        NullrootGeometry.rotateY(poseStack, (float) Math.atan2(dx, dz));
        NullrootGeometry.rotateX(poseStack, (float) Math.acos(Mth.clamp(dy / length, -1.0, 1.0)));
        float width = thickness / NullrootModels.SEGMENT_WIDTH;
        // A little longer than the gap so neighbouring boxes overlap at the bends.
        poseStack.scale(width, (float) (length * 1.08), width);
        unit.render(poseStack, consumer, light, overlay, color);
        poseStack.popPose();
    }
}
