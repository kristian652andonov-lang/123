package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/**
 * A dark, featureless figure far away. It is a camera-facing billboard (upright, turning only
 * around its vertical axis) with the generated silhouette texture, rendered as a translucent
 * entity so distance fog swallows it naturally. A standing figure vanishes the moment the player
 * looks straight at it or comes closer; a moving one runs along the edge of the view and is gone
 * when looked at.
 */
final class SilhouetteRenderer {
    private static final ResourceLocation TEXTURE = NullMod.id("textures/gui/visual/silhouette.png");
    private static final PoseStack IDENTITY = new PoseStack();
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();
    /** Our own small buffer, so drawing the figure never flushes vanilla's pending batches. */
    private static MultiBufferSource.BufferSource buffers;
    /** cos(7 degrees): looking straight at it. */
    private static final double LOOK_DOT = 0.9925;

    private static boolean active;
    private static boolean moving;
    private static boolean fleeing;
    private static double x;
    private static double y;
    private static double z;
    private static double prevX;
    private static double prevY;
    private static double prevZ;
    private static double velX;
    private static double velZ;
    private static int age;
    private static int lifetime;
    private static int variant;
    private static float height;
    private static float width;
    private static ClientLevel level;

    private SilhouetteRenderer() {
    }

    /**
     * Places a figure at the edge of the view. {@code moving}: it runs sideways out of view
     * (distant movement) instead of standing still.
     */
    static boolean spawn(float intensity, int duration, long seed, boolean movingFigure) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel clientLevel = mc.level;
        if (player == null || clientLevel == null || ClientLimits.maxVisualEffects() <= 0) {
            return false;
        }
        RandomSource random = RandomSource.create(seed);
        Vec3 eye = player.getEyePosition();
        for (int attempt = 0; attempt < 16; attempt++) {
            float side = random.nextBoolean() ? 1.0F : -1.0F;
            float yawDeg = player.getYRot() + side * (22.0F + random.nextFloat() * 18.0F);
            double distance = movingFigure ? 28.0 + random.nextDouble() * 26.0 : 40.0 + random.nextDouble() * 42.0;
            float yaw = yawDeg * Mth.DEG_TO_RAD;
            double px = player.getX() - Mth.sin(yaw) * distance;
            double pz = player.getZ() + Mth.cos(yaw) * distance;
            Double ground = groundAt(clientLevel, px, pz);
            if (ground == null) {
                continue;
            }
            Vec3 chest = new Vec3(px, ground + 1.3, pz);
            BlockHitResult hit = clientLevel.clip(new ClipContext(eye, chest, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(chest) > 4.0) {
                continue;
            }
            level = clientLevel;
            x = px;
            y = ground;
            z = pz;
            prevX = x;
            prevY = y;
            prevZ = z;
            age = 0;
            lifetime = Mth.clamp(duration, 40, 400);
            variant = random.nextInt(4);
            height = 1.85F + random.nextFloat() * 0.35F + (variant == 1 ? 0.25F : 0.0F);
            width = height * 0.5F;
            moving = movingFigure;
            fleeing = false;
            if (moving) {
                // Run across the line of sight, away from the centre of the view.
                double dx = px - player.getX();
                double dz = pz - player.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                double perpX = -dz / len;
                double perpZ = dx / len;
                Vec3 look = player.getViewVector(1.0F);
                double away = perpX * look.x + perpZ * look.z;
                double sign = away > 0.0 ? -1.0 : 1.0;
                double speed = 0.16 + 0.1 * Mth.clamp(intensity, 0.0F, 1.0F);
                velX = perpX * sign * speed;
                velZ = perpZ * sign * speed;
            } else {
                velX = 0.0;
                velZ = 0.0;
            }
            active = true;
            return true;
        }
        return false;
    }

    static void clear() {
        active = false;
        level = null;
    }

    static void clientTick() {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.level != level) {
            clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        prevX = x;
        prevY = y;
        prevZ = z;
        age++;
        Vec3 eye = player.getEyePosition();
        double dx = x - eye.x;
        double dy = (y + height * 0.6) - eye.y;
        double dz = z - eye.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (age >= lifetime || distSq < 22.0 * 22.0) {
            clear();
            return;
        }
        Vec3 look = player.getViewVector(1.0F);
        double dot = (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(distSq);
        if (dot > LOOK_DOT) {
            if (!moving) {
                // Looked at directly: it is simply not there any more.
                clear();
                return;
            }
            if (!fleeing) {
                fleeing = true;
                lifetime = Math.min(lifetime, age + 8);
                velX *= 3.0;
                velZ *= 3.0;
            }
        }
        if (moving) {
            double nx = x + velX;
            double nz = z + velZ;
            Double ground = groundAt(level, nx, nz);
            if (ground == null || Math.abs(ground - y) > 2.0) {
                clear();
                return;
            }
            x = nx;
            z = nz;
            y = ground;
        }
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != level) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double fx = Mth.lerp(partial, prevX, x);
        double fy = Mth.lerp(partial, prevY, y);
        double fz = Mth.lerp(partial, prevZ, z);
        Vec3 cam = event.getCamera().getPosition();
        float dx = (float) (fx - cam.x);
        float dy = (float) (fy - cam.y);
        float dz = (float) (fz - cam.z);
        float horizontal = Mth.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.5F) {
            return;
        }
        // Right-hand vector of the viewer, so the quad always faces the camera.
        float rx = -dz / horizontal * width * 0.5F;
        float rz = dx / horizontal * width * 0.5F;
        float u0 = variant * 0.25F;
        float u1 = u0 + 0.25F;
        int light = LevelRenderer.getLightColor(level, LIGHT_POS.set(fx, fy + 1.0, fz));

        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
        }
        RenderType type = RenderType.entityTranslucent(TEXTURE);
        VertexConsumer consumer = buffers.getBuffer(type);
        PoseStack.Pose pose = IDENTITY.last();
        vertex(consumer, pose, dx - rx, dy, dz - rz, u0, 1.0F, light);
        vertex(consumer, pose, dx + rx, dy, dz + rz, u1, 1.0F, light);
        vertex(consumer, pose, dx + rx, dy + height, dz + rz, u1, 0.0F, light);
        vertex(consumer, pose, dx - rx, dy + height, dz - rz, u0, 0.0F, light);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        buffers.endBatch(type);
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float px, float py, float pz, float u, float v, int light) {
        consumer.addVertex(pose, px, py, pz)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** Height a figure would stand at, or null if the spot is unloaded, wet, leafy or cramped. */
    private static Double groundAt(ClientLevel clientLevel, double px, double pz) {
        int bx = Mth.floor(px);
        int bz = Mth.floor(pz);
        if (!clientLevel.hasChunk(bx >> 4, bz >> 4)) {
            return null;
        }
        int top = clientLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        if (top <= clientLevel.getMinBuildHeight()) {
            return null;
        }
        BlockPos below = new BlockPos(bx, top - 1, bz);
        BlockState ground = clientLevel.getBlockState(below);
        if (ground.isAir() || ground.is(BlockTags.LEAVES) || !ground.getFluidState().isEmpty()) {
            return null;
        }
        BlockPos feet = below.above();
        if (!clientLevel.getBlockState(feet).getCollisionShape(clientLevel, feet).isEmpty()
                || !clientLevel.getBlockState(feet.above()).getCollisionShape(clientLevel, feet.above()).isEmpty()) {
            return null;
        }
        return (double) top;
    }
}
