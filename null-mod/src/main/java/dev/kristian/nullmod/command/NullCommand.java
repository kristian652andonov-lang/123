package dev.kristian.nullmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionStageManager;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.field.FieldManager;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code /null status|stage|pace|field|reset|pause|resume|speed|events|event}. Needs permission
 * level 2 (operators, or cheats enabled in singleplayer).
 * <p>
 * {@code /null pace} changes after how many in-game nights stages 2-5 begin and how long a player
 * has to survive in the NULL dimension for Stage 6. The values are the world's server config
 * ({@code progression.*}): they are set in the loaded config and written to
 * {@code serverconfig/null-server.toml} at once. Only the server uses them (clients get the stage
 * and its progress from the server), so the change is in effect immediately for everyone.
 */
public final class NullCommand {
    private static final long RESET_CONFIRM_TICKS = 20L * 15L;
    /** Limits of /null pace, the same as the config's. */
    private static final double MIN_NIGHTS = 0.1;
    private static final double MAX_NIGHTS = 10000.0;
    private static final double MIN_MINUTES = 0.25;
    private static final double MAX_MINUTES = 1440.0;
    /** Survivors listed by /null status at most. */
    private static final int MAX_LISTED = 10;
    private static long resetRequestedAt = Long.MIN_VALUE;

    /** Where {@code /null field} can take the field's cycle; "cracking" is late in the day, the sun badly cracked. */
    private enum FieldStep {
        DAY("day", FieldCycle.Phase.DAY, 0.0),
        CRACKING("cracking", FieldCycle.Phase.DAY, 0.6),
        SHATTER("shatter", FieldCycle.Phase.SHATTER, 0.0),
        NIGHT("night", FieldCycle.Phase.NIGHT, 0.0),
        HUNT("hunt", FieldCycle.Phase.HUNT, 0.0),
        DAWN("dawn", FieldCycle.Phase.DAWN, 0.0);

        final String word;
        final FieldCycle.Phase phase;
        /** How far into the phase, 0..1. */
        final double into;

        FieldStep(String word, FieldCycle.Phase phase, double into) {
            this.word = word;
            this.phase = phase;
            this.into = into;
        }
    }

    private NullCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Commands are registered for every server start: a confirmation pending in a world that
        // was closed must never confirm a reset in the next one (game times are per world).
        resetRequestedAt = Long.MIN_VALUE;
        LiteralArgumentBuilder<CommandSourceStack> field = Commands.literal("field")
                .executes(ctx -> fieldStatus(ctx.getSource()));
        for (FieldStep step : FieldStep.values()) {
            field.then(Commands.literal(step.word).executes(ctx -> field(ctx.getSource(), step)));
        }
        dispatcher.register(Commands.literal("null")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("stage")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.argument("stage", IntegerArgumentType.integer(1, CorruptionStage.HARVEST.number()))
                                .executes(ctx -> stage(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "stage")))))
                .then(Commands.literal("pace")
                        .executes(ctx -> showPace(ctx.getSource()))
                        .then(Commands.literal("reset").executes(ctx -> resetPace(ctx.getSource())))
                        .then(Commands.argument("stage", IntegerArgumentType.integer(CorruptionStage.UNDER_MIDDLE.number(), CorruptionStage.HARVEST.number()))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, MAX_NIGHTS))
                                        .executes(ctx -> setPace(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "stage"),
                                                DoubleArgumentType.getDouble(ctx, "value"))))))
                .then(field)
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())))
                .then(Commands.literal("pause").executes(ctx -> pause(ctx.getSource(), true)))
                .then(Commands.literal("resume").executes(ctx -> pause(ctx.getSource(), false)))
                .then(Commands.literal("speed")
                        .executes(ctx -> showSpeed(ctx.getSource()))
                        .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.0, 100.0))
                                .executes(ctx -> speed(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "multiplier")))))
                .then(Commands.literal("events").executes(ctx -> listEvents(ctx.getSource())))
                .then(Commands.literal("event")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        NullEventRegistry.all().stream().map(NullEventDefinition::id), builder))
                                .executes(ctx -> event(ctx, List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> event(ctx, EntityArgument.getPlayers(ctx, "targets")))))));
    }

    // ------------------------------------------------------------------ status

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        CorruptionData data = CorruptionManager.data(server);
        CorruptionStage stage = data.stage();
        double ticks = data.corruptionTicks();
        int stagePercent = Math.round(CorruptionStageManager.progressWithin(stage, ticks) * 100.0F);
        long night = CorruptionStageManager.nightsPassed(ticks);
        boolean moving = moving(server, data);

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("NULL ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                .append(Component.translatable("null.command.status.stage", stage.number(), stage.displayName(), stagePercent)
                        .withStyle(ChatFormatting.WHITE)));
        if (stage.atLeast(CorruptionStage.NULL)) {
            lines.add(Component.translatable("null.command.status.night_final", night).withStyle(ChatFormatting.GRAY));
        } else {
            CorruptionStage next = stage.next();
            double untilNext = CorruptionStageManager.ticksUntilNext(stage, ticks);
            String eta = moving ? Ticks.format(untilNext / CorruptionManager.effectiveSpeed(server)) : "-";
            lines.add(Component.translatable("null.command.status.night", night, next.number(), next.displayName(),
                    nights(CorruptionStageManager.nights(next)), nights(untilNext / CorruptionStageManager.TICKS_PER_NIGHT), eta)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (stage == CorruptionStage.NULL) {
            addSurvivors(server, moving, lines);
        } else if (stage == CorruptionStage.HARVEST) {
            lines.add(fieldLine(server).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("null.command.status.final").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("null.command.status.speed",
                String.format(Locale.ROOT, "%.2f", data.speed()), String.format(Locale.ROOT, "%.2f", NullConfig.get(NullConfig.PROGRESSION_SPEED)),
                Component.translatable(data.paused() ? "null.command.status.paused" : "null.command.status.running"))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("null.command.status.moon",
                Math.round(data.moonProgress() * 100.0F),
                Component.translatable(data.moonGone() ? "null.command.status.moon_gone" : "null.command.status.moon_present"))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("null.command.status.time", Ticks.format(data.activeTicks())).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("null.command.status.highest", data.highestStage().number(), NullEventRegistry.size())
                .withStyle(ChatFormatting.DARK_GRAY));
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return stage.number();
    }

    /** Stage 5: how long everyone in the NULL dimension has survived there, towards HARVEST. */
    private static void addSurvivors(MinecraftServer server, boolean moving, List<Component> lines) {
        double required = CorruptionStageManager.harvestSurvivalTicks();
        lines.add(Component.translatable("null.command.status.survival", Ticks.format(required)).withStyle(ChatFormatting.GRAY));
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        List<ServerPlayer> inNull = level == null ? List.of() : level.players();
        if (inNull.isEmpty()) {
            lines.add(Component.translatable("null.command.status.survival_nobody").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        int listed = 0;
        for (ServerPlayer player : inNull) {
            if (listed == MAX_LISTED) {
                lines.add(Component.translatable("null.command.status.survival_more", inNull.size() - MAX_LISTED)
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            lines.add(Component.translatable("null.command.status.survival_player", player.getGameProfile().getName(),
                    Ticks.format(NullDimensionManager.INSTANCE.survivalTime(player)), Ticks.format(required))
                    .withStyle(ChatFormatting.GRAY));
            listed++;
        }
        if (!moving) {
            lines.add(Component.translatable("null.command.status.survival_held").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Stage 6: where the field's shared day and night stand. */
    private static MutableComponent fieldLine(MinecraftServer server) {
        ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
        int inField = level == null ? 0 : level.players().size();
        FieldCycle.Phase phase = FieldManager.INSTANCE.phase();
        return Component.translatable("null.command.status.field", phaseName(phase), Ticks.format(FieldManager.INSTANCE.remaining()), inField);
    }

    /** The clock is counting and its speed is above zero. */
    private static boolean moving(MinecraftServer server, CorruptionData data) {
        return CorruptionManager.effectiveSpeed(server) > 0.0 && !data.paused() && NullConfig.get(NullConfig.PROGRESSION_ENABLED);
    }

    // ------------------------------------------------------------------ stage

    private static int stage(CommandSourceStack source, int number) {
        CorruptionStage stage = CorruptionStage.byNumber(number);
        CorruptionManager.setStage(source.getServer(), stage);
        source.sendSuccess(() -> Component.translatable("null.command.stage.set", stage.number(), stage.displayName()), true);
        if (stage == CorruptionStage.HARVEST) {
            source.sendSuccess(() -> Component.translatable("null.command.stage.harvest").withStyle(ChatFormatting.GRAY), false);
        }
        return number;
    }

    // ------------------------------------------------------------------ pace

    private static int showPace(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        CorruptionData data = CorruptionManager.data(server);
        CorruptionStage current = data.stage();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("null.command.pace.header", CorruptionStageManager.nightsPassed(data.corruptionTicks()))
                .withStyle(ChatFormatting.DARK_RED));
        for (CorruptionStage stage : CorruptionStage.values()) {
            MutableComponent line;
            if (stage == CorruptionStage.START) {
                line = Component.translatable("null.command.pace.first", stage.number(), stage.displayName());
            } else if (stage == CorruptionStage.HARVEST) {
                line = Component.translatable("null.command.pace.harvest", stage.number(), stage.displayName(),
                        number(NullConfig.get(NullConfig.HARVEST_SURVIVAL_MINUTES)));
            } else {
                line = Component.translatable("null.command.pace.stage", stage.number(), stage.displayName(),
                        nights(CorruptionStageManager.nights(stage)));
            }
            line.withStyle(stage == current ? ChatFormatting.WHITE : ChatFormatting.GRAY);
            if (stage == current) {
                line.append(Component.translatable("null.command.pace.current").withStyle(ChatFormatting.DARK_RED));
            }
            lines.add(line);
        }
        lines.add(Component.translatable("null.command.pace.footer",
                String.format(Locale.ROOT, "%.2f", CorruptionManager.effectiveSpeed(server))).withStyle(ChatFormatting.DARK_GRAY));
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return lines.size();
    }

    private static int setPace(CommandSourceStack source, int number, double value) {
        MinecraftServer server = source.getServer();
        if (!NullConfig.loaded()) {
            source.sendFailure(Component.translatable("null.command.pace.unavailable"));
            return 0;
        }
        if (number == CorruptionStage.HARVEST.number()) {
            if (value < MIN_MINUTES || value > MAX_MINUTES) {
                source.sendFailure(Component.translatable("null.command.pace.minutes_range", number(MIN_MINUTES), number(MAX_MINUTES)));
                return 0;
            }
            NullConfig.HARVEST_SURVIVAL_MINUTES.set(value);
            savePace(server);
            source.sendSuccess(() -> Component.translatable("null.command.pace.harvest_set", number(value)), true);
            return 1;
        }
        if (value < MIN_NIGHTS || value > MAX_NIGHTS) {
            source.sendFailure(Component.translatable("null.command.pace.nights_range", number(MIN_NIGHTS), number(MAX_NIGHTS)));
            return 0;
        }
        // Index = stage number; 2..5 are on the clock. Start from what is in effect now.
        double[] old = new double[CorruptionStage.NULL.number() + 1];
        for (int n = CorruptionStage.UNDER_MIDDLE.number(); n <= CorruptionStage.NULL.number(); n++) {
            old[n] = CorruptionStageManager.nights(CorruptionStage.byNumber(n));
        }
        double earliest = number == CorruptionStage.UNDER_MIDDLE.number() ? MIN_NIGHTS : old[number - 1] + CorruptionStageManager.MIN_GAP_NIGHTS;
        if (value < earliest) {
            // The stage before has to begin first: the order is never changed by moving a stage down.
            CorruptionStage before = CorruptionStage.byNumber(number - 1);
            CorruptionStage stage = CorruptionStage.byNumber(number);
            source.sendFailure(Component.translatable("null.command.pace.too_early", stage.number(), stage.displayName(),
                    before.number(), before.displayName(), nights(old[number - 1]), nights(earliest)));
            return 0;
        }
        // Later stages that would no longer come after it move later, keeping their distance.
        double[] pace = CorruptionStageManager.pushLater(old, number, value);
        List<Component> pushed = new ArrayList<>();
        for (int n = number + 1; n <= CorruptionStage.NULL.number(); n++) {
            if (pace[n] > MAX_NIGHTS) {
                source.sendFailure(Component.translatable("null.command.pace.too_late", number(MAX_NIGHTS)));
                return 0;
            }
            if (pace[n] != old[n]) {
                CorruptionStage moved = CorruptionStage.byNumber(n);
                pushed.add(Component.translatable("null.command.pace.pushed_entry", moved.number(), moved.displayName(), nights(pace[n])));
            }
        }
        NullConfig.STAGE_2_NIGHTS.set(pace[CorruptionStage.UNDER_MIDDLE.number()]);
        NullConfig.STAGE_3_NIGHTS.set(pace[CorruptionStage.MIDDLE.number()]);
        NullConfig.STAGE_4_NIGHTS.set(pace[CorruptionStage.ABOVE_MIDDLE.number()]);
        NullConfig.STAGE_5_NIGHTS.set(pace[CorruptionStage.NULL.number()]);
        savePace(server);

        CorruptionStage stage = CorruptionStage.byNumber(number);
        source.sendSuccess(() -> Component.translatable("null.command.pace.set", stage.number(), stage.displayName(), nights(value)), true);
        if (!pushed.isEmpty()) {
            MutableComponent list = Component.empty();
            for (int i = 0; i < pushed.size(); i++) {
                if (i > 0) {
                    list.append(", ");
                }
                list.append(pushed.get(i));
            }
            source.sendSuccess(() -> Component.translatable("null.command.pace.pushed", list).withStyle(ChatFormatting.YELLOW), true);
        }
        CorruptionData data = CorruptionManager.data(server);
        CorruptionStage due = CorruptionStageManager.stageFor(data.corruptionTicks());
        if (due.number() > data.stage().number()) {
            source.sendSuccess(() -> Component.translatable("null.command.pace.due",
                    nights(data.corruptionTicks() / CorruptionStageManager.TICKS_PER_NIGHT), due.number(), due.displayName())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int resetPace(CommandSourceStack source) {
        if (!NullConfig.loaded()) {
            source.sendFailure(Component.translatable("null.command.pace.unavailable"));
            return 0;
        }
        for (ModConfigSpec.DoubleValue value : List.of(NullConfig.STAGE_2_NIGHTS, NullConfig.STAGE_3_NIGHTS,
                NullConfig.STAGE_4_NIGHTS, NullConfig.STAGE_5_NIGHTS, NullConfig.HARVEST_SURVIVAL_MINUTES)) {
            value.set(value.getDefault());
        }
        savePace(source.getServer());
        source.sendSuccess(() -> Component.translatable("null.command.pace.reset"), true);
        return showPace(source);
    }

    /** Writes the world's server config and tells the clients the stage's new progress. */
    private static void savePace(MinecraftServer server) {
        NullConfig.SPEC.save();
        CorruptionManager.paceChanged(server);
    }

    // ------------------------------------------------------------------ the field

    private static int fieldStatus(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (CorruptionManager.stage(server) != CorruptionStage.HARVEST) {
            source.sendFailure(Component.translatable("null.command.field.harvest_only"));
            return 0;
        }
        MutableComponent line = fieldLine(server);
        source.sendSuccess(() -> line, false);
        return FieldManager.INSTANCE.phase().id() + 1;
    }

    private static int field(CommandSourceStack source, FieldStep step) {
        MinecraftServer server = source.getServer();
        if (CorruptionManager.stage(server) != CorruptionStage.HARVEST) {
            source.sendFailure(Component.translatable("null.command.field.harvest_only"));
            return 0;
        }
        int elapsed = (int) Math.round(FieldCycle.length(step.phase) * step.into);
        FieldManager.INSTANCE.skipTo(server, step.phase, elapsed);
        source.sendSuccess(() -> Component.translatable("null.command.field.set",
                Component.translatable("null.command.field.step." + step.word)), true);
        return step.ordinal() + 1;
    }

    private static Component phaseName(FieldCycle.Phase phase) {
        return Component.translatable("null.command.field.phase." + phase.name().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ the rest

    private static int reset(CommandSourceStack source) {
        long now = source.getServer().overworld().getGameTime();
        boolean pending = resetRequestedAt != Long.MIN_VALUE && now >= resetRequestedAt
                && now - resetRequestedAt <= RESET_CONFIRM_TICKS;
        if (!pending) {
            resetRequestedAt = now;
            source.sendSuccess(() -> Component.translatable("null.command.reset.confirm").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        resetRequestedAt = Long.MIN_VALUE;
        CorruptionManager.reset(source.getServer());
        source.sendSuccess(() -> Component.translatable("null.command.reset.done"), true);
        return 1;
    }

    private static int pause(CommandSourceStack source, boolean paused) {
        CorruptionManager.setPaused(source.getServer(), paused);
        source.sendSuccess(() -> Component.translatable(paused ? "null.command.paused" : "null.command.resumed"), true);
        return 1;
    }

    private static int showSpeed(CommandSourceStack source) {
        CorruptionData data = CorruptionManager.data(source.getServer());
        source.sendSuccess(() -> Component.translatable("null.command.speed.current",
                String.format(Locale.ROOT, "%.2f", data.speed()), String.format(Locale.ROOT, "%.2f", NullConfig.get(NullConfig.PROGRESSION_SPEED))), false);
        return (int) Math.round(data.speed() * 100.0);
    }

    private static int speed(CommandSourceStack source, double multiplier) {
        CorruptionManager.setSpeed(source.getServer(), multiplier);
        source.sendSuccess(() -> Component.translatable("null.command.speed.set", String.format(Locale.ROOT, "%.2f", multiplier)), true);
        return (int) Math.round(multiplier * 100.0);
    }

    private static int listEvents(CommandSourceStack source) {
        CorruptionStage stage = CorruptionManager.stage(source.getServer());
        MutableComponent header = Component.translatable("null.command.events.header", NullEventRegistry.size());
        source.sendSuccess(() -> header, false);
        for (CorruptionStage s : CorruptionStage.values()) {
            List<String> ids = new ArrayList<>();
            for (NullEventDefinition event : NullEventRegistry.all()) {
                if (event.minStage() == s) {
                    ids.add(event.id());
                }
            }
            if (ids.isEmpty()) {
                continue;
            }
            ChatFormatting color = s == stage ? ChatFormatting.WHITE : ChatFormatting.GRAY;
            Component line = Component.literal(s.number() + " " + s.title() + ": ").withStyle(ChatFormatting.DARK_RED)
                    .append(Component.literal(String.join(", ", ids)).withStyle(color));
            source.sendSuccess(() -> line, false);
        }
        return NullEventRegistry.size();
    }

    private static int event(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        Optional<NullEventDefinition> event = NullEventRegistry.get(id);
        if (event.isEmpty()) {
            source.sendFailure(Component.translatable("null.command.event.unknown", id));
            return 0;
        }
        CorruptionContext corruption = CorruptionManager.context(source.getServer());
        int ran = 0;
        for (ServerPlayer player : targets) {
            if (RandomEventManager.INSTANCE.force(player, event.get(), corruption)) {
                ran++;
            }
        }
        int count = ran;
        if (count == 0) {
            source.sendFailure(Component.translatable("null.command.event.failed", id));
        } else {
            source.sendSuccess(() -> Component.translatable("null.command.event.ran", id, count), true);
        }
        return count;
    }

    // ------------------------------------------------------------------ formatting

    /** "3", "2.5", "0.25": at most two decimals, none when whole. */
    static String number(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.indexOf('.') >= 0) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }
        return "-0".equals(text) ? "0" : text;
    }

    /** "1 night", "3 nights", "2.5 nights". */
    private static Component nights(double value) {
        String text = number(value);
        return "1".equals(text)
                ? Component.translatable("null.command.nights.one")
                : Component.translatable("null.command.nights", text);
    }
}
