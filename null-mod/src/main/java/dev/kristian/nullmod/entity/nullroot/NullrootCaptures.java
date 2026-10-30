package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.WorldMemory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side bookkeeping of NULLROOT captures. A capture binds one player to the organism that
 * grabbed them (and, inside a colony, to the central mass they are pulled into). While it lasts
 * the player cannot walk or jump (two transient attribute modifiers) and is dragged toward the
 * hold point; after the variant's capture time the player dies if
 * {@link NullConfig#NULLROOT_CAPTURE_LETHAL} is on, otherwise they are let go. Hurting the
 * organism enough within that time frees them.
 * <p>
 * The modifiers are transient (never written to the player file) and are removed on every way
 * a capture can end: release, death, logout, dimension change, respawn, server stop, removal of
 * the organism, and a stale-capture sweep every tick that does not depend on the organism
 * ticking. Server thread only.
 */
public final class NullrootCaptures {
    public static final ResourceLocation SPEED_MODIFIER = NullMod.id("nullroot_capture_speed");
    public static final ResourceLocation JUMP_MODIFIER = NullMod.id("nullroot_capture_jump");

    /** A hold breaks if the player ends up this far from the organism (teleport, pearl...). */
    private static final double BREAK_DISTANCE = 16.0;
    /** Ticks without the holding organism ticking before the capture counts as abandoned. */
    private static final int STALE_TICKS = 20;
    /** Colony centres this close to the grabbing organism take over the hold. */
    private static final double ANCHOR_SEARCH = 18.0;

    /** Why a capture ended. */
    public enum Reason {
        /** The organism (or its anchor) was hurt enough. */
        DAMAGE(true),
        /** The capture ran its course and captures are not lethal. */
        FINISHED(false),
        /** The capture ran its course and the player was killed. */
        CONSUMED(false),
        /** The organism died, withered, was unloaded or removed. */
        ORGANISM_GONE(false),
        /** The player died, left, changed dimension or game mode. */
        PLAYER_GONE(false),
        /** The player got too far away from the organism. */
        BROKEN(true),
        /** Reset, stage change or server stop. */
        RESET(false);

        private final boolean escaped;

        Reason(boolean escaped) {
            this.escaped = escaped;
        }

        /** True if the player got away (rather than the capture simply ending). */
        public boolean escaped() {
            return escaped;
        }
    }

    /** One player held by one organism. */
    public static final class Capture {
        private final ServerPlayer player;
        private final NullrootEntity grabber;
        @Nullable
        private NullrootEntity anchor;
        private final int duration;
        private int age;
        private float damage;
        private int lastTick;

        private Capture(ServerPlayer player, NullrootEntity grabber, int duration, int tick) {
            this.player = player;
            this.grabber = grabber;
            this.duration = Math.max(1, duration);
            this.lastTick = tick;
        }

        public ServerPlayer player() {
            return player;
        }

        public NullrootEntity grabber() {
            return grabber;
        }

        /** The colony centre the player is pulled into, if any. */
        @Nullable
        public NullrootEntity anchor() {
            return anchor;
        }

        /** The organism whose hold point the player is pulled to. */
        public NullrootEntity holder() {
            return anchor != null ? anchor : grabber;
        }

        public int duration() {
            return duration;
        }

        public int age() {
            return age;
        }

        /** 0..1 progress of the capture sequence. */
        public float progress() {
            return Mth.clamp(age / (float) duration, 0.0F, 1.0F);
        }
    }

    private static final Map<UUID, Capture> CAPTURES = new LinkedHashMap<>();

    private NullrootCaptures() {
    }

    // ------------------------------------------------------------------ queries

    public static boolean isCaptured(Player player) {
        return CAPTURES.containsKey(player.getUUID());
    }

    @Nullable
    public static Capture captureOf(Player player) {
        return CAPTURES.get(player.getUUID());
    }

    public static Collection<Capture> all() {
        return Collections.unmodifiableCollection(CAPTURES.values());
    }

    /** The capture an organism is holding (as grabber or anchor), or null. */
    @Nullable
    public static Capture heldBy(NullrootEntity organism) {
        for (Capture capture : CAPTURES.values()) {
            if (capture.grabber == organism || capture.anchor == organism) {
                return capture;
            }
        }
        return null;
    }

    /**
     * True if this player may be grabbed right now: alive, in survival or adventure (never
     * creative or spectator, decided by the real game mode), awake and not already held.
     */
    public static boolean canCapture(ServerPlayer player) {
        return holdable(player) && !player.isSleeping() && !CAPTURES.containsKey(player.getUUID());
    }

    private static boolean holdable(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && !player.isSpectator()
                && player.gameMode.isSurvival();
    }

    // ------------------------------------------------------------------ the capture sequence

    /** Starts a capture; returns false if the player cannot be grabbed. */
    static boolean start(NullrootEntity grabber, ServerPlayer player) {
        if (!canCapture(player) || !(grabber.level() instanceof ServerLevel level) || player.level() != level) {
            return false;
        }
        player.stopRiding();
        if (player.isFallFlying()) {
            player.stopFallFlying();
        }
        player.setSprinting(false);
        player.stopUsingItem();
        applyModifiers(player);
        Capture capture = new Capture(player, grabber, grabber.captureDuration(), player.server.getTickCount());
        CAPTURES.put(player.getUUID(), capture);
        if (grabber.variant() != NullrootVariant.CENTRAL) {
            NullrootEntity anchor = findAnchor(level, grabber);
            if (anchor != null) {
                capture.anchor = anchor;
                anchor.onAnchorStarted(player);
            }
        }
        grabber.onCaptureStarted(player);
        WorldMemory.remember(player.server, "nullroot:captured", player.getUUID(), grabber.variant().id());
        return true;
    }

    /** Advances a capture by one tick. Called by the grabbing organism from its own tick. */
    static void tick(Capture capture) {
        if (CAPTURES.get(capture.player.getUUID()) != capture) {
            return;
        }
        ServerPlayer player = capture.player;
        NullrootEntity grabber = capture.grabber;
        capture.lastTick = player.server.getTickCount();
        if (!holdable(player) || player.isSleeping() || player.level() != grabber.level() || !grabber.isAlive() || grabber.isRemoved()) {
            release(capture, Reason.PLAYER_GONE);
            return;
        }
        NullrootEntity anchor = capture.anchor;
        if (anchor != null && (!anchor.isAlive() || anchor.isRemoved() || anchor.level() != grabber.level())) {
            capture.anchor = null;
            if (!anchor.isRemoved()) {
                anchor.onAnchorEnded();
            }
        }
        if (player.position().distanceToSqr(grabber.rootPoint()) > BREAK_DISTANCE * BREAK_DISTANCE) {
            release(capture, Reason.BROKEN);
            return;
        }
        capture.age++;
        float progress = capture.progress();
        grabber.setCaptureProgress(progress);
        if (capture.anchor != null) {
            capture.anchor.setCaptureProgress(progress);
        }
        if (capture.age % 20 == 0) {
            // Something else may have cleared the player's modifiers; the hold does not loosen.
            applyModifiers(player);
        }
        pull(capture, progress);
        player.resetFallDistance();
        if (capture.age >= capture.duration) {
            finish(capture);
        }
    }

    /** Drags the player toward the hold point with small velocity corrections. */
    private static void pull(Capture capture, float progress) {
        ServerPlayer player = capture.player;
        NullrootEntity holder = capture.holder();
        Vec3 target = holder.holdPoint(player);
        Vec3 delta = target.subtract(player.position());
        double distance = delta.length();
        boolean lift = holder.face() == Direction.DOWN;
        double vx = 0.0;
        double vy = 0.0;
        double vz = 0.0;
        if (distance > 0.05) {
            double speed = Math.min(distance * 0.35, 0.16 + 0.16 * progress);
            vx = delta.x / distance * speed;
            vy = delta.y / distance * speed;
            vz = delta.z / distance * speed;
        }
        if (lift) {
            vy = Mth.clamp(vy, -0.3, 0.3);
        } else {
            vy = player.onGround() ? 0.0 : Mth.clamp(vy, -0.35, 0.0);
        }
        if (distance > 0.3 || capture.age % 5 == 0) {
            player.setDeltaMovement(vx, vy, vz);
            player.hurtMarked = true;
        }
    }

    private static void finish(Capture capture) {
        boolean lethal = NullConfig.get(NullConfig.NULLROOT_CAPTURE_LETHAL);
        NullrootEntity consumer = capture.anchor != null && capture.anchor.isAlive() ? capture.anchor : capture.grabber;
        ServerPlayer player = capture.player;
        release(capture, lethal ? Reason.CONSUMED : Reason.FINISHED);
        if (!lethal) {
            return;
        }
        consumer.consumeEffect();
        player.hurt(NullrootDamage.source(player.serverLevel(), consumer), Float.MAX_VALUE);
        if (player.isDeadOrDying()) {
            WorldMemory.remember(player.server, "nullroot:killed", player.getUUID(), consumer.variant().id());
        }
    }

    // ------------------------------------------------------------------ ending captures

    /** Ends a capture; idempotent. Always removes the modifiers. */
    static void release(Capture capture, Reason reason) {
        UUID id = capture.player.getUUID();
        if (CAPTURES.get(id) != capture) {
            stripModifiers(capture.player);
            return;
        }
        CAPTURES.remove(id);
        stripModifiers(capture.player);
        capture.player.resetFallDistance();
        if (!capture.grabber.isRemoved()) {
            capture.grabber.onCaptureEnded(reason);
        }
        NullrootEntity anchor = capture.anchor;
        if (anchor != null && anchor != capture.grabber && !anchor.isRemoved()) {
            anchor.onAnchorEnded();
        }
        if (reason.escaped()) {
            WorldMemory.remember(capture.player.server, "nullroot:escaped", id, capture.grabber.variant().id());
        }
    }

    /** Frees a player if they are held. Always removes the modifiers. */
    public static void release(ServerPlayer player, Reason reason) {
        Capture capture = CAPTURES.get(player.getUUID());
        if (capture != null) {
            release(capture, reason);
        }
        stripModifiers(player);
    }

    /** Ends every capture this organism takes part in (it died, withered or was removed). */
    static void releaseFor(NullrootEntity organism, Reason reason) {
        for (Capture capture : new ArrayList<>(CAPTURES.values())) {
            if (capture.grabber == organism) {
                release(capture, reason);
            } else if (capture.anchor == organism) {
                // The centre is gone; the organism that grabbed them keeps hold on its own.
                capture.anchor = null;
            }
        }
    }

    /** Ends every capture (reset, stage change, server stop). */
    public static void releaseAll(Reason reason) {
        for (Capture capture : new ArrayList<>(CAPTURES.values())) {
            release(capture, reason);
        }
        CAPTURES.clear();
    }

    /** Damage to a holding organism counts toward letting go. */
    static void onOrganismDamaged(NullrootEntity organism, float amount) {
        if (CAPTURES.isEmpty() || amount <= 0.0F) {
            return;
        }
        for (Capture capture : new ArrayList<>(CAPTURES.values())) {
            if (capture.grabber == organism || capture.anchor == organism) {
                capture.damage += amount;
                if (capture.damage >= organism.variant().releaseDamage()) {
                    release(capture, Reason.DAMAGE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ modifiers

    static void applyModifiers(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_MODIFIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.addOrUpdateTransientModifier(new AttributeModifier(JUMP_MODIFIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Removes both capture modifiers if present. Safe to call on anyone at any time. */
    public static void stripModifiers(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_MODIFIER);
        }
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(JUMP_MODIFIER);
        }
    }

    public static boolean hasModifiers(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        return speed != null && speed.hasModifier(SPEED_MODIFIER) || jump != null && jump.hasModifier(JUMP_MODIFIER);
    }

    // ------------------------------------------------------------------ anchors

    /** The nearest free colony centre around the grabbing organism, preferring its own colony. */
    @Nullable
    private static NullrootEntity findAnchor(ServerLevel level, NullrootEntity grabber) {
        NullrootEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (NullrootEntity organism : NullrootManager.INSTANCE.organisms()) {
            if (organism == grabber || organism.variant() != NullrootVariant.CENTRAL || organism.level() != level
                    || !organism.isAlive() || organism.isRemoved() || organism.state().holding() || organism.isWithering()) {
                continue;
            }
            double distance = organism.distanceToSqr(grabber);
            if (distance > ANCHOR_SEARCH * ANCHOR_SEARCH) {
                continue;
            }
            double score = grabber.sameColony(organism) ? distance * 0.25 : distance;
            if (score < bestScore) {
                bestScore = score;
                best = organism;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ game events

    /** Sweeps captures whose organism stopped ticking or whose player is gone. */
    static void onServerTick(ServerTickEvent.Post event) {
        if (CAPTURES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        for (Capture capture : new ArrayList<>(CAPTURES.values())) {
            if (capture.player.isRemoved() || capture.player.hasDisconnected()) {
                release(capture, Reason.PLAYER_GONE);
            } else if (capture.grabber.isRemoved() || !capture.grabber.isAlive() || now - capture.lastTick > STALE_TICKS) {
                release(capture, Reason.ORGANISM_GONE);
            }
        }
    }

    static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            release(player, Reason.PLAYER_GONE);
        }
    }

    static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            release(player, Reason.PLAYER_GONE);
        }
    }

    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            release(player, Reason.PLAYER_GONE);
        }
    }

    static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        stripModifiers(event.getEntity());
    }

    static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            release(player, Reason.PLAYER_GONE);
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        releaseAll(Reason.RESET);
    }

    /** Captures of players in a level, for tests and debugging. */
    public static List<Capture> capturesIn(ServerLevel level) {
        List<Capture> out = new ArrayList<>();
        for (Capture capture : CAPTURES.values()) {
            if (capture.player.level() == level) {
                out.add(capture);
            }
        }
        return out;
    }
}
