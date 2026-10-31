package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.stalker.StalkerFigure;
import dev.kristian.nullmod.client.stalker.StalkerModel;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.entity.stalker.StalkerSounds;
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
 * A dark, featureless figure far away. Standing and moving figures are camera-facing billboards
 * (upright, turning only around their vertical axis) with the generated silhouette texture,
 * rendered as translucent entities so distance fog swallows them. A standing figure vanishes the
 * moment the player looks straight at it or comes closer; a moving one runs along the edge of the
 * view and is gone when looked at.
 * <p>
 * <b>The runner</b> (the NULL stage): the same tall black figure as the stalker of Stages 1-4 (its
 * 3D model, two pin-prick eyes), standing in the distance with its eyes on the player. When they
 * look at it, or after a moment, it sprints straight at them, far faster than anyone can run,
 * footfalls hammering, and is gone an instant before it reaches them; looking away makes it be
 * gone too. With intense effects switched off it never sprints: it takes a few slow steps closer and
 * fades out. Its vanishing never flashes; with low flashing settings it fades out over a few frames
 * instead of disappearing at once.
 */
final class SilhouetteRenderer {
    private static final ResourceLocation TEXTURE = NullMod.id("textures/gui/visual/silhouette.png");
    private static final PoseStack IDENTITY = new PoseStack();
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();
    /** Our own small buffers, so drawing the figure never flushes vanilla's pending batches. */
    private static MultiBufferSource.BufferSource buffers;
    private static MultiBufferSource.BufferSource figureBuffers;
    /** cos(7 degrees): looking straight at it. */
    private static final double LOOK_DOT = 0.9925;
    /** cos(15 degrees): looking at the runner (it is small and far away). */
    private static final double RUNNER_LOOK_DOT = 0.966;
    /** cos(~55 degrees): roughly the edge of the screen. */
    private static final double ON_SCREEN_DOT = 0.57;
    /** Chance that a standing figure of the NULL stage is a runner. */
    private static final float NULL_RUNNER_CHANCE = 0.35F;

    private static final int STAND = 0;
    private static final int MOVE = 1;
    private static final int RUNNER = 2;

    private static final int WAIT = 0;
    private static final int SPRINT = 1;
    private static final int STEP = 2;
    private static final int FADE = 3;

    /** Blocks per tick at full sprint (a player sprints at about 0.28). */
    private static final double SPRINT_SPEED = 0.62;
    /** Blocks per tick when it only steps closer. */
    private static final double STEP_SPEED = 0.07;
    /** Length of one sprinting stride in blocks (one footfall). */
    private static final double SPRINT_STRIDE = 2.4;

    private static boolean active;
    private static int mode;
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

    private static int runPhase;
    private static int phaseTicks;
    private static int waitLimit;
    private static double speed;
    private static float stride;
    private static float prevStride;
    private static float alpha = 1.0F;
    private static float prevAlpha = 1.0F;
    private static int offScreenTicks;
    private static float strength;
    private static boolean modelFigure;
    private static final RandomSource SOUND_RANDOM = RandomSource.create();
    private static final StalkerModel.Pose POSE = new StalkerModel.Pose();

    private SilhouetteRenderer() {
    }

    /**
     * Places a figure at the edge of the view. {@code moving}: it runs sideways out of view
     * (distant movement) instead of standing still. In the NULL stage a standing one is sometimes
     * a runner, which runs at the player instead of vanishing when looked at.
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
            place(clientLevel, px, ground, pz, duration, random);
            mode = movingFigure ? MOVE : STAND;
            if (movingFigure) {
                // Run across the line of sight, away from the centre of the view.
                double dx = px - player.getX();
                double dz = pz - player.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                double perpX = -dz / len;
                double perpZ = dx / len;
                Vec3 look = player.getViewVector(1.0F);
                double away = perpX * look.x + perpZ * look.z;
                double sign = away > 0.0 ? -1.0 : 1.0;
                double runSpeed = 0.16 + 0.1 * Mth.clamp(intensity, 0.0F, 1.0F);
                velX = perpX * sign * runSpeed;
                velZ = perpZ * sign * runSpeed;
            } else if (ClientCorruptionState.stage() == CorruptionStage.NULL && random.nextFloat() < NULL_RUNNER_CHANCE
                    && StalkerFigure.available() && runnable(clientLevel, player, px, ground, pz)) {
                // It waits to be looked at; then it comes.
                becomeRunner(intensity, lifetime);
            }
            return true;
        }
        return false;
    }

    /**
     * The runner on its own: in front of the player, 26-40 blocks away, on ground it can run over
     * straight to them. It starts running when looked at, or after a moment anyway.
     */
    static boolean spawnRunner(float intensity, int duration, long seed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel clientLevel = mc.level;
        if (player == null || clientLevel == null || ClientLimits.maxVisualEffects() <= 0) {
            return false;
        }
        RandomSource random = RandomSource.create(seed);
        Vec3 eye = player.getEyePosition();
        for (int attempt = 0; attempt < 24; attempt++) {
            float yawDeg = player.getYRot() + (random.nextFloat() * 2.0F - 1.0F) * (attempt < 12 ? 22.0F : 40.0F);
            double distance = 26.0 + random.nextDouble() * 14.0;
            float yaw = yawDeg * Mth.DEG_TO_RAD;
            double px = player.getX() - Mth.sin(yaw) * distance;
            double pz = player.getZ() + Mth.cos(yaw) * distance;
            Double ground = groundAt(clientLevel, px, pz);
            if (ground == null || !clearLine(clientLevel, player, eye, new Vec3(px, ground + 1.6, pz))
                    || !runnable(clientLevel, player, px, ground, pz)) {
                continue;
            }
            place(clientLevel, px, ground, pz, Math.max(duration, 200), random);
            becomeRunner(intensity, 20 + random.nextInt(30));
            return true;
        }
        return false;
    }

    private static void place(ClientLevel clientLevel, double px, double ground, double pz, int duration, RandomSource random) {
        level = clientLevel;
        x = px;
        y = ground;
        z = pz;
        prevX = x;
        prevY = y;
        prevZ = z;
        velX = 0.0;
        velZ = 0.0;
        age = 0;
        lifetime = Mth.clamp(duration, 40, 400);
        variant = random.nextInt(4);
        height = 1.85F + random.nextFloat() * 0.35F + (variant == 1 ? 0.25F : 0.0F);
        width = height * 0.5F;
        fleeing = false;
        modelFigure = false;
        alpha = 1.0F;
        prevAlpha = 1.0F;
        active = true;
    }

    private static void becomeRunner(float intensity, int waitTicks) {
        mode = RUNNER;
        runPhase = WAIT;
        phaseTicks = 0;
        waitLimit = waitTicks;
        speed = 0.0;
        stride = 0.0F;
        prevStride = 0.0F;
        offScreenTicks = 0;
        strength = Mth.clamp(intensity, 0.0F, 1.0F);
        modelFigure = StalkerFigure.available();
        lifetime = Math.max(lifetime, waitTicks + 160);
    }

    /** True if the ground from the spot to the player is open and never steps more than a block and a half at a time. */
    private static boolean runnable(ClientLevel clientLevel, LocalPlayer player, double px, double ground, double pz) {
        double dx = player.getX() - px;
        double dz = player.getZ() - pz;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int samples = (int) (distance / 2.0);
        double last = ground;
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            Double at = groundAt(clientLevel, px + dx * t, pz + dz * t);
            if (at == null || Math.abs(at - last) > 1.6) {
                return false;
            }
            last = at;
        }
        return Math.abs(last - player.getY()) <= 2.5;
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
        prevStride = stride;
        prevAlpha = alpha;
        age++;
        if (mode == RUNNER) {
            runnerTick(player);
            return;
        }
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
            if (mode != MOVE) {
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
        if (mode == MOVE) {
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

    // ------------------------------------------------------------------ the runner

    private static void runnerTick(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 chest = new Vec3(x, y + 1.4, z);
        Vec3 to = chest.subtract(eye);
        double distance = to.length();
        double dot = distance < 1.0E-3 ? 1.0 : player.getViewVector(1.0F).dot(to) / distance;
        phaseTicks++;
        switch (runPhase) {
            case WAIT -> {
                boolean lookedAt = dot > RUNNER_LOOK_DOT && clearLine(level, player, eye, chest);
                if (lookedAt || phaseTicks > waitLimit) {
                    runPhase = ClientLimits.intenseAllowed() ? SPRINT : STEP;
                    phaseTicks = 0;
                } else if (age >= lifetime || distance < 12.0) {
                    clear();
                }
            }
            case SPRINT -> sprintTick(player, eye, chest, distance, dot);
            case STEP -> {
                if (!advance(player, STEP_SPEED, false) || phaseTicks > 36) {
                    runPhase = FADE;
                    phaseTicks = 0;
                }
            }
            default -> {
                // FADE
                alpha = Math.max(0.0F, alpha - (phaseTicks > 0 && ClientLimits.intenseAllowed() ? 0.34F : 0.05F));
                if (alpha <= 0.0F) {
                    clear();
                }
            }
        }
        if (active && age > lifetime + 400) {
            clear();
        }
    }

    private static void sprintTick(LocalPlayer player, Vec3 eye, Vec3 chest, double distance, double dot) {
        // Looking away: it is gone.
        offScreenTicks = dot < ON_SCREEN_DOT ? offScreenTicks + 1 : 0;
        if (offScreenTicks >= 2 || (phaseTicks % 2 == 0 && !clearLine(level, player, eye, chest))) {
            clear();
            return;
        }
        double horizontal = Math.sqrt((player.getX() - x) * (player.getX() - x) + (player.getZ() - z) * (player.getZ() - z));
        if (horizontal < 2.2 + speed * 2.0) {
            vanishBeforeReaching();
            return;
        }
        double maxSpeed = SPRINT_SPEED + 0.1 * strength;
        speed = Math.min(maxSpeed, speed + 0.07);
        if (!advance(player, speed, true)) {
            clear();
        }
    }

    /** It is gone an instant before it reaches them: a jolt (scaled by their settings), never a flash. */
    private static void vanishBeforeReaching() {
        VisualDistortionManager.add(VisualDistortionManager.Kind.SHAKE, 0.35F + 0.25F * strength, 8);
        if (ClientLimits.flashing() < 0.5F) {
            // Low flashing settings: a fade over a few frames instead of a pop.
            runPhase = FADE;
            phaseTicks = 1;
            return;
        }
        clear();
    }

    /** One step towards the player over the ground; false if the way is blocked (it is gone then). */
    private static boolean advance(LocalPlayer player, double blocksPerTick, boolean sprinting) {
        double dx = player.getX() - x;
        double dz = player.getZ() - z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-3) {
            return false;
        }
        double step = Math.min(blocksPerTick, distance);
        double nx = x + dx / distance * step;
        double nz = z + dz / distance * step;
        Double ground = groundAt(level, nx, nz);
        if (ground == null || Math.abs(ground - y) > 1.6) {
            return false;
        }
        x = nx;
        z = nz;
        y = ground;
        double strideLength = sprinting ? SPRINT_STRIDE : 1.35;
        float before = stride;
        stride += (float) (step / strideLength * Math.PI);
        if (Mth.floor(stride / Mth.PI) != Mth.floor(before / Mth.PI)) {
            footfall(player, sprinting);
        }
        return true;
    }

    private static void footfall(LocalPlayer player, boolean sprinting) {
        Vec3 feet = new Vec3(x, y + 0.1, z);
        double distance = Math.sqrt(player.distanceToSqr(feet));
        if (sprinting) {
            float volume = (float) Mth.clamp(0.75 + 0.4 * (1.0 - distance / 40.0), 0.6, 1.15);
            AudioCorruptionManager.playAt(StalkerSounds.RUNNER_FOOTSTEPS.get(), AudioCorruptionManager.Channel.FOOTSTEPS, feet,
                    volume, 0.92F + SOUND_RANDOM.nextFloat() * 0.16F, 56.0F);
        } else {
            AudioCorruptionManager.playAt(StalkerSounds.FOOTSTEPS.get(), AudioCorruptionManager.Channel.FOOTSTEPS, feet,
                    0.7F, 0.88F + SOUND_RANDOM.nextFloat() * 0.08F, 40.0F);
        }
    }

    private static boolean clearLine(ClientLevel clientLevel, LocalPlayer player, Vec3 from, Vec3 to) {
        BlockHitResult hit = clientLevel.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 1.0;
    }

    // ------------------------------------------------------------------ drawing

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
        int light = LevelRenderer.getLightColor(level, LIGHT_POS.set(fx, fy + 1.0, fz));
        if (mode == RUNNER && modelFigure) {
            renderFigure(event, dx, dy, dz, horizontal, light, partial);
            return;
        }
        // Right-hand vector of the viewer, so the quad always faces the camera.
        float rx = -dz / horizontal * width * 0.5F;
        float rz = dx / horizontal * width * 0.5F;
        float u0 = variant * 0.25F;
        float u1 = u0 + 0.25F;
        int a = Mth.clamp((int) (Mth.lerp(partial, prevAlpha, alpha) * 255.0F), 0, 255);

        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
        }
        RenderType type = RenderType.entityTranslucent(TEXTURE);
        VertexConsumer consumer = buffers.getBuffer(type);
        PoseStack.Pose pose = IDENTITY.last();
        vertex(consumer, pose, dx - rx, dy, dz - rz, u0, 1.0F, light, a);
        vertex(consumer, pose, dx + rx, dy, dz + rz, u1, 1.0F, light, a);
        vertex(consumer, pose, dx + rx, dy + height, dz + rz, u1, 0.0F, light, a);
        vertex(consumer, pose, dx - rx, dy + height, dz - rz, u0, 0.0F, light, a);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        buffers.endBatch(type);
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** The runner: the stalker's own model, facing the camera, eyes on the player. */
    private static void renderFigure(RenderLevelStageEvent event, float dx, float dy, float dz, float horizontal, int light, float partial) {
        if (figureBuffers == null) {
            figureBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(32768));
        }
        // Facing the camera: Minecraft's yaw of the direction from the figure to the viewer.
        float yaw = (float) (Mth.atan2(-dz, -dx) * Mth.RAD_TO_DEG) - 90.0F;
        float eyeDy = dy + 2.2F;
        StalkerModel.Pose pose = POSE;
        pose.clear();
        float runningStride = Mth.lerp(partial, prevStride, stride);
        if (runPhase == SPRINT) {
            pose.run = (float) Mth.clamp(speed / 0.45, 0.0, 1.0);
            pose.walk = runningStride;
        } else if (runPhase == STEP || runPhase == FADE) {
            pose.walk = runningStride;
            pose.walkAmount = runPhase == STEP ? 1.0F : 0.0F;
        }
        // The head lifts or lowers to look the viewer in the eyes.
        pose.headPitch = Mth.clamp((float) Math.atan2(eyeDy, horizontal), -0.6F, 0.6F);
        pose.time = age + partial;
        float fade = Mth.lerp(partial, prevAlpha, alpha);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            PoseStack stack = new PoseStack();
            stack.translate(dx, dy, dz);
            StalkerFigure.render(stack, figureBuffers, yaw, pose, light, fade, 1.0F);
            figureBuffers.endBatch();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float px, float py, float pz, float u, float v, int light, int a) {
        consumer.addVertex(pose, px, py, pz)
                .setColor(255, 255, 255, a)
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
