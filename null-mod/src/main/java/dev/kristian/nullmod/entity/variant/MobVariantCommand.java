package dev.kristian.nullmod.entity.variant;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /null mob <corrupted|parasite|clear> [targets]} for operators (permission level 2, like
 * the rest of {@code /null}; Brigadier merges it under the existing literal). Without targets it
 * takes the mob the executing player looks at within 32 blocks, else the nearest one within 16
 * blocks of where the command runs (so the showcase can also run it from the server's console
 * with a selector). Works at any stage: the mobs it makes are an operator's and stay until the
 * stage is moved back. Reports how many mobs changed.
 */
public final class MobVariantCommand {
    private static final double LOOK_REACH = 32.0;
    private static final double NEAR_REACH = 16.0;
    /** Parasites made by the command start part-grown, so their tentacles show at once. */
    private static final float COMMAND_GROWTH = 0.4F;

    private MobVariantCommand() {
    }

    static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("null")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mob")
                        .requires(source -> source.hasPermission(2))
                        .then(mode("corrupted", MobVariant.CORRUPTED))
                        .then(mode("parasite", MobVariant.PARASITE))
                        .then(mode("clear", MobVariant.NONE))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String name, MobVariant variant) {
        return Commands.literal(name)
                .executes(ctx -> run(ctx.getSource(), defaultTarget(ctx.getSource(), variant), variant))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> run(ctx.getSource(), EntityArgument.getEntities(ctx, "targets"), variant)));
    }

    private static int run(CommandSourceStack source, Collection<? extends Entity> targets, MobVariant variant) {
        if (targets.isEmpty()) {
            source.sendFailure(Component.translatable("null.command.mob.no_target"));
            return 0;
        }
        if (variant != MobVariant.NONE && !VariantConfig.enabled()) {
            source.sendFailure(Component.translatable("null.command.mob.disabled"));
            return 0;
        }
        MobVariantManager manager = MobVariantManager.INSTANCE;
        int changed = 0;
        int refused = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Mob mob)) {
                refused++;
                continue;
            }
            if (variant == MobVariant.NONE) {
                if (manager.set(mob, MobVariant.NONE, 0.0F, false)) {
                    changed++;
                }
                continue;
            }
            if (!MobVariantRules.canBecomeVariant(mob, false)) {
                refused++;
                continue;
            }
            if (manager.set(mob, variant, COMMAND_GROWTH, true)) {
                changed++;
            }
        }
        int count = changed;
        int skipped = refused;
        if (count == 0) {
            source.sendFailure(Component.translatable(skipped > 0 ? "null.command.mob.refused" : "null.command.mob.unchanged", skipped));
            return 0;
        }
        String key = switch (variant) {
            case NONE -> "null.command.mob.cleared";
            case CORRUPTED -> "null.command.mob.corrupted";
            case PARASITE -> "null.command.mob.parasite";
        };
        source.sendSuccess(() -> Component.translatable(key, count, skipped), true);
        return count;
    }

    /** The mob the executing entity looks at, else the nearest suitable mob around the command's position. */
    private static List<Entity> defaultTarget(CommandSourceStack source, MobVariant variant) {
        Entity self = source.getEntity();
        if (self != null) {
            Mob looked = lookedAt(self, LOOK_REACH);
            if (looked != null) {
                return List.of(looked);
            }
        }
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        Mob best = null;
        double bestDistance = NEAR_REACH * NEAR_REACH;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, AABB.ofSize(pos, NEAR_REACH * 2.0, NEAR_REACH * 2.0, NEAR_REACH * 2.0),
                candidate -> candidate != self && suits(candidate, variant))) {
            double distance = mob.distanceToSqr(pos);
            if (distance <= bestDistance) {
                best = mob;
                bestDistance = distance;
            }
        }
        return best == null ? List.of() : List.of(best);
    }

    private static boolean suits(Mob mob, MobVariant variant) {
        if (variant == MobVariant.NONE) {
            return MobVariantManager.variantOf(mob) != MobVariant.NONE;
        }
        return MobVariantRules.canBecomeVariant(mob, false);
    }

    /** The mob under the entity's crosshair within {@code reach} blocks (blocks in between stop the look). */
    @Nullable
    static Mob lookedAt(Entity viewer, double reach) {
        Vec3 eye = viewer.getEyePosition();
        Vec3 look = viewer.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(reach));
        BlockHitResult block = viewer.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        AABB box = viewer.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(viewer, eye, end, box,
                entity -> entity instanceof Mob && entity.isAlive() && !entity.isSpectator(), eye.distanceToSqr(end));
        return hit != null && hit.getEntity() instanceof Mob mob ? mob : null;
    }
}
