package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.entity.PlayerLikeEntity;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * One appearance of the figure for one player: a small state machine ticked by
 * {@link StalkerManager} every server tick until it reports that it is over. It owns at most one
 * {@link StalkerEntity} and drives it; everything it changes in the world goes through
 * {@link TemporaryChanges} (which undoes it later on its own) or {@code SafeBlockPlacer} (the
 * miner's tunnel through natural rock).
 */
abstract class Encounter {
    final StalkerKind kind;
    final ServerLevel level;
    final UUID owner;
    /** 0 early in Stage 1 .. 1 in late Stage 4: how bold the figure is (closer, longer, more). */
    final float boldness;
    /** Started by {@code /null event}: placement rules relax and it happens whatever the time. */
    final boolean forced;
    /** Run by {@code /null showcase}: whatever it does should happen in front of the player, soon. */
    boolean showcase;
    final RandomSource random;
    int age;
    @Nullable
    StalkerEntity figure;

    Encounter(StalkerKind kind, ServerPlayer owner, float boldness, boolean forced, RandomSource random) {
        this.kind = kind;
        this.level = owner.serverLevel();
        this.owner = owner.getUUID();
        this.boldness = Mth.clamp(boldness, 0.0F, 1.0F);
        this.forced = forced;
        this.random = random;
    }

    /** Finds its place and sets itself up; false if it cannot happen here and now. Called once. */
    abstract boolean begin(ServerPlayer player);

    /**
     * One server tick. {@code player} is the owner while they are online, alive and in this level,
     * otherwise null (most encounters simply end then). Returns false when it is over.
     */
    abstract boolean tick(@Nullable ServerPlayer player);

    /** It is over (or cut short): the figure is gone. World changes are undone by {@link TemporaryChanges}. */
    void end() {
        removeFigure();
    }

    /** Linear blend by boldness: {@code early} in Stage 1, {@code late} in Stage 4. */
    final float bold(float early, float late) {
        return Mth.lerp(boldness, early, late);
    }

    final int boldTicks(float earlySeconds, float lateSeconds) {
        return Math.round(bold(earlySeconds, lateSeconds) * 20.0F);
    }

    // ------------------------------------------------------------------ the figure

    /** Places the figure (feet at {@code feet}, facing {@code yaw}); null if it cannot stand there now. */
    @Nullable
    final StalkerEntity spawnFigure(Vec3 feet, float yaw, @Nullable ServerPlayer player) {
        StalkerEntity created = StalkerModule.STALKER.get().create(level);
        if (created == null) {
            return null;
        }
        created.moveTo(feet.x, feet.y, feet.z, yaw, 0.0F);
        created.faceYaw(yaw);
        if (!created.inTickingChunk()) {
            // Beyond the simulation distance it would only stand frozen.
            return null;
        }
        created.setManaged(player);
        if (!level.addFreshEntity(created)) {
            return null;
        }
        figure = created;
        return created;
    }

    final boolean figureGone() {
        return figure == null || figure.hasVanished();
    }

    /** Tells the figure its encounter is still running (it removes itself otherwise). */
    final void drive() {
        if (figure != null) {
            figure.drive();
        }
    }

    final void removeFigure() {
        if (figure != null && !figure.hasVanished()) {
            figure.vanish(null);
        }
        figure = null;
    }

    // ------------------------------------------------------------------ sounds

    /**
     * Plays one of the figure's sounds at a block for every player within {@code range} of it,
     * through each one's own audio settings (players without NULL hear nothing).
     */
    final void soundAt(SoundEvent sound, BlockPos pos, float volume, float pitch, double range) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) <= range * range) {
                PlayerLikeEntity.sendSound(player, sound, pos, volume, pitch);
            }
        }
    }

    /** A random pitch around 1 for natural variation. */
    final float pitch(float spread) {
        return 1.0F + (random.nextFloat() * 2.0F - 1.0F) * spread;
    }
}
