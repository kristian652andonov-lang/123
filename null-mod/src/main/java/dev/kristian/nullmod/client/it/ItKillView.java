package dev.kristian.nullmod.client.it;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.entity.it.ItAnimation;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.entity.it.ItKillPath;
import dev.kristian.nullmod.network.ClientEffectPayload;
import javax.annotation.Nullable;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the victim goes through while IT kills them: the view is held on its face (they cannot
 * look away), the screen shakes hard and jerks at the grab, the blow and every bite, a dark red
 * vignette closes in and throbs like a heartbeat, and at the end the screen goes black and stays
 * black until they respawn. Driven by the kill stage and ticks IT syncs (and the {@code it.kill}
 * effect, which may arrive a tick before the passenger link does).
 * <p>
 * Accessibility: every shake and jerk goes through {@link VisualDistortionManager}, which scales
 * them with the player's shake settings; the heartbeat throb scales with
 * {@link ClientLimits#flashing()} and is steady at zero (nothing ever flashes); with intense effects
 * disabled the screen goes black right after the grab, so the kill is heard, not seen.
 */
final class ItKillView {
    static final ResourceLocation VIGNETTE_LAYER = NullMod.id("it_kill_vignette");
    static final ResourceLocation FADE_LAYER = NullMod.id("it_kill_fade");
    private static final ResourceLocation VIGNETTE = NullMod.id("textures/gui/it/kill_vignette.png");
    /** Ticks the it.kill effect waits for the passenger link to arrive. */
    private static final int PENDING_TICKS = 40;

    @Nullable
    private static ItEntity holder;
    private static int pendingId = -1;
    private static int pendingTicks;
    private static int ticks;
    private static float vignette;
    private static float vignetteO;
    private static float fade;
    private static float fadeO;
    private static boolean dead;
    private static boolean riding;
    @Nullable
    private static ClientLevel lastLevel;

    private ItKillView() {
    }

    /**
     * {@code it.kill}: the server has seized this player (data: IT's entity id). The grab is felt at
     * once, here: IT's grab event and this effect always arrive before the passenger link does (the
     * server sends that at the end of its tick), so {@link #onEvent} cannot tell the grab was ours.
     */
    static void onKillEffect(ClientEffectPayload payload) {
        if (pendingTicks > 0 && pendingId == payload.data()) {
            return;
        }
        pendingId = payload.data();
        pendingTicks = PENDING_TICKS;
        VisualDistortionManager.add(VisualDistortionManager.Kind.CAMERA_JERK, 0.6F, 10);
        VisualDistortionManager.shake(0.6F, 20);
    }

    /** True while the local player is in IT's claw (the camera is not theirs). */
    static boolean active() {
        return holder != null;
    }

    static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            reset();
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        vignetteO = vignette;
        fadeO = fade;
        ItEntity it = player.getVehicle() instanceof ItEntity held && held.killStage() != ItEntity.KILL_NONE ? held : null;
        if (it == null && pendingTicks > 0) {
            pendingTicks--;
        }
        boolean nowRiding = player.getVehicle() instanceof ItEntity;
        if (nowRiding && !riding) {
            // Not "press shift to get down": nobody gets down.
            mc.gui.setOverlayMessage(Component.empty(), false);
        }
        riding = nowRiding;
        if (it != null) {
            if (holder != it) {
                holder = it;
                ticks = 0;
                dead = false;
                pendingTicks = 0;
            }
            ticks++;
            held(it);
            return;
        }
        holder = null;
        if (player.isDeadOrDying() && (dead || fade > 0.5F)) {
            // Eaten: the screen stays black behind the death screen until they respawn.
            dead = true;
            fade = 1.0F;
            vignette = Math.max(0.0F, vignette - 0.05F);
            return;
        }
        dead = false;
        vignette = Math.max(0.0F, vignette - 0.06F);
        fade = Math.max(0.0F, fade - 0.04F);
    }

    private static void held(ItEntity it) {
        float kt = it.killTicks();
        boolean intense = ClientLimits.intenseAllowed();
        float fadeStart = intense ? ItKillPath.SWALLOW_AT - 6.0F : ItKillPath.SEIZE_END;
        float fadeLength = intense ? 12.0F : 16.0F;
        float fadeTarget = Mth.clamp((kt - fadeStart) / fadeLength, 0.0F, 1.0F);
        fade += (fadeTarget - fade) * 0.35F;
        float vignetteTarget = Mth.clamp(kt / 12.0F, 0.0F, 1.0F) * (0.6F + 0.4F * Mth.clamp((kt - ItKillPath.LIFT_END) / 30.0F, 0.0F, 1.0F));
        vignette += (vignetteTarget - vignette) * 0.25F;
        // Held up in its hand, it never stops shaking.
        if (ticks % 8 == 0 && kt > 4.0F && kt < ItKillPath.END) {
            float strength = kt < ItKillPath.LIFT_END ? 0.25F : 0.32F;
            VisualDistortionManager.shake(strength, 12);
        }
    }

    /** IT's loud moments, felt by the one it holds. */
    static void onEvent(ItEntity it, byte event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.getVehicle() != it) {
            return;
        }
        boolean intense = ClientLimits.intenseAllowed();
        switch (event) {
            case ItEntity.EVENT_PIERCE -> {
                VisualDistortionManager.add(VisualDistortionManager.Kind.CAMERA_JERK, 0.9F, 12);
                VisualDistortionManager.shake(0.9F, 30);
                VisualDistortionManager.add(VisualDistortionManager.Kind.DARKEN, 0.35F, 40);
                if (intense) {
                    VisualDistortionManager.add(VisualDistortionManager.Kind.RGB_SPLIT, 0.3F, 30);
                }
            }
            case ItEntity.EVENT_BITE, ItEntity.EVENT_TEAR -> {
                VisualDistortionManager.add(VisualDistortionManager.Kind.CAMERA_JERK, 0.5F, 8);
                VisualDistortionManager.shake(0.7F, 16);
            }
            case ItEntity.EVENT_SWALLOW -> VisualDistortionManager.shake(0.5F, 30);
            default -> {
            }
        }
    }

    static void onRemoved(ItEntity it) {
        if (holder == it) {
            holder = null;
        }
    }

    /**
     * Before every frame (after the mouse has turned the player): the view is turned onto its face,
     * gently over the first few ticks, then held there.
     */
    static void onRenderFrame(DeltaTracker delta) {
        ItEntity it = holder;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (it == null || player == null || player.getVehicle() != it || mc.isPaused()) {
            return;
        }
        float pt = delta.getGameTimeDeltaPartialTick(true);
        ItAnimation anim = it.animation();
        Vec3 face = anim.faceKnown && it.position().distanceToSqr(anim.faceX, anim.faceY, anim.faceZ) < 8.0 * 8.0
                ? new Vec3(anim.faceX, anim.faceY, anim.faceZ) : it.toWorld(new Vec3(0.0, 6.1, 2.4));
        Vec3 eye = player.getEyePosition(pt);
        double dx = face.x - eye.x;
        double dy = face.y - eye.y;
        double dz = face.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-3 && Math.abs(dy) < 1.0E-3) {
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
        float blend = Mth.clamp((ticks + pt) / 6.0F, 0.0F, 1.0F);
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float newYaw = currentYaw + Mth.wrapDegrees(yaw - currentYaw) * blend;
        float newPitch = Mth.clamp(currentPitch + (pitch - currentPitch) * blend, -90.0F, 90.0F);
        player.setYRot(newYaw);
        player.setXRot(newPitch);
        player.yRotO = newYaw;
        player.xRotO = newPitch;
        player.yHeadRot = newYaw;
        player.yHeadRotO = newYaw;
    }

    /** The dark red vignette, throbbing like a heartbeat (steady when flashing is off). */
    static void renderVignette(GuiGraphics graphics, DeltaTracker delta) {
        float pt = delta.getGameTimeDeltaPartialTick(true);
        float amount = Mth.lerp(pt, vignetteO, vignette);
        if (amount <= 0.003F || Minecraft.getInstance().level == null) {
            return;
        }
        float time = (ticks + pt) / 20.0F;
        // Two beats a second, the second weaker, getting faster as it goes on.
        float rate = 1.4F + 0.8F * Mth.clamp(ticks / 100.0F, 0.0F, 1.0F);
        float cycle = (time * rate) % 1.0F;
        float beat = pulse(cycle, 0.0F) + 0.6F * pulse(cycle, 0.22F);
        float alpha = Mth.clamp(amount * (0.72F + 0.28F * beat * ClientLimits.flashing()), 0.0F, 1.0F);
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blit(VIGNETTE, 0, 0, graphics.guiWidth(), graphics.guiHeight(), 0.0F, 0.0F, 256, 256, 256, 256);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private static float pulse(float cycle, float at) {
        float d = cycle - at;
        if (d < 0.0F || d > 0.16F) {
            return 0.0F;
        }
        return Mth.sin(d / 0.16F * Mth.PI);
    }

    /** Black over everything at the end of the kill (and until respawn). */
    static void renderFade(GuiGraphics graphics, DeltaTracker delta) {
        float pt = delta.getGameTimeDeltaPartialTick(true);
        float amount = Mth.clamp(Mth.lerp(pt, fadeO, fade), 0.0F, 1.0F);
        if (amount <= 0.003F || Minecraft.getInstance().level == null) {
            return;
        }
        int alpha = Mth.clamp((int) (amount * 255.0F), 0, 255);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
    }

    /** Logout or a new world: nothing carries over. */
    static void reset() {
        holder = null;
        pendingId = -1;
        pendingTicks = 0;
        ticks = 0;
        vignette = 0.0F;
        vignetteO = 0.0F;
        fade = 0.0F;
        fadeO = 0.0F;
        dead = false;
        riding = false;
    }
}
