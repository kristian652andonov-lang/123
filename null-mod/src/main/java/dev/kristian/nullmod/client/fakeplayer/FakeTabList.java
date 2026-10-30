package dev.kristian.nullmod.client.fakeplayer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.visitor.PlayerLikeRenderer;
import dev.kristian.nullmod.entity.SkinSet;
import dev.kristian.nullmod.network.FakePlayersPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The player list (Tab) while simulated players exist. It replaces the vanilla list and is
 * laid out exactly like it: the same columns, row backgrounds, ping bars, heads (only where
 * vanilla shows heads), scoreboard column or hearts, spectators last and greyed. Real
 * players come first with their real heads, then the simulated ones with heads from NULL's
 * skins, up to vanilla's 80 entries, under a server-like header and footer. It also shows
 * in singleplayer, where vanilla would not. Some simulated entries are unstable: names that
 * are partly unreadable, rows that drop out for a moment, pings that go unknown.
 */
public final class FakeTabList {
    private static final int MAX_ENTRIES = 80;
    private static final int SPECTATOR_COLOR = -1862270977;
    private static final ResourceLocation PING_UNKNOWN = ResourceLocation.withDefaultNamespace("icon/ping_unknown");
    private static final ResourceLocation PING_1 = ResourceLocation.withDefaultNamespace("icon/ping_1");
    private static final ResourceLocation PING_2 = ResourceLocation.withDefaultNamespace("icon/ping_2");
    private static final ResourceLocation PING_3 = ResourceLocation.withDefaultNamespace("icon/ping_3");
    private static final ResourceLocation PING_4 = ResourceLocation.withDefaultNamespace("icon/ping_4");
    private static final ResourceLocation PING_5 = ResourceLocation.withDefaultNamespace("icon/ping_5");
    private static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation HEART_ABSORBING_FULL = ResourceLocation.withDefaultNamespace("hud/heart/absorbing_full_blinking");
    private static final ResourceLocation HEART_ABSORBING_HALF = ResourceLocation.withDefaultNamespace("hud/heart/absorbing_half_blinking");

    /** Vanilla's order: spectators last, then by team name, then by name. */
    private static final Comparator<PlayerInfo> PLAYER_ORDER = Comparator
            .<PlayerInfo>comparingInt(info -> info.getGameMode() == GameType.SPECTATOR ? 1 : 0)
            .thenComparing(info -> info.getTeam() == null ? "" : info.getTeam().getName())
            .thenComparing(info -> info.getProfile().getName(), String::compareToIgnoreCase);

    /** One line of the list. */
    private record Row(Component name, @Nullable ResourceLocation face, boolean hat, boolean upsideDown, int latency,
                       boolean spectator, int score, @Nullable Component formattedScore, int scoreWidth) {
    }

    // The simulated rows are rebuilt at most ten times a second, not every frame.
    private static List<FakePlayersPayload.Entry> cachedSource = List.of();
    private static long cachedSlot = Long.MIN_VALUE;
    @Nullable
    private static Objective cachedObjective;
    private static List<Row> cachedRows = List.of();

    private FakeTabList() {
    }

    static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!VanillaGuiLayers.TAB_LIST.equals(event.getName())) {
            return;
        }
        List<FakePlayersPayload.Entry> fakes = ClientCorruptionState.fakePlayers();
        if (fakes.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (minecraft.level == null || minecraft.player == null || connection == null
                || minecraft.options.hideGui || !minecraft.options.keyPlayerList.isDown()) {
            return;
        }
        event.setCanceled(true);
        render(event.getGuiGraphics(), minecraft, connection, fakes);
    }

    /** Drops the cached rows (on logout). */
    static void reset() {
        cachedSource = List.of();
        cachedSlot = Long.MIN_VALUE;
        cachedObjective = null;
        cachedRows = List.of();
    }

    private static void render(GuiGraphics graphics, Minecraft minecraft, ClientPacketListener connection, List<FakePlayersPayload.Entry> fakes) {
        Font font = minecraft.font;
        Scoreboard scoreboard = minecraft.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.LIST);
        boolean hearts = objective != null && objective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS;
        long now = Util.getMillis();

        List<PlayerInfo> real = new ArrayList<>(connection.getListedOnlinePlayers());
        real.sort(PLAYER_ORDER);
        List<Row> rows = new ArrayList<>(Math.min(MAX_ENTRIES, real.size() + fakes.size()));
        PlayerTabOverlay overlay = minecraft.gui.getTabList();
        for (PlayerInfo info : real) {
            if (rows.size() >= MAX_ENTRIES) {
                break;
            }
            rows.add(realRow(minecraft, overlay, info, scoreboard, objective, hearts, font));
        }
        for (Row row : simulatedRows(fakes, objective, hearts, font, now)) {
            if (rows.size() >= MAX_ENTRIES) {
                break;
            }
            rows.add(row);
        }
        Component header = header(minecraft, now);
        Component footer = Component.translatable("null.tablist.footer", real.size() + fakes.size()).withStyle(ChatFormatting.GRAY);
        draw(graphics, minecraft, connection, font, rows, objective, hearts, header, footer);
    }

    private static Row realRow(Minecraft minecraft, PlayerTabOverlay overlay, PlayerInfo info, Scoreboard scoreboard,
                               @Nullable Objective objective, boolean hearts, Font font) {
        GameProfile profile = info.getProfile();
        Player player = minecraft.level.getPlayerByUUID(profile.getId());
        boolean upsideDown = player != null && LivingEntityRenderer.isEntityUpsideDown(player);
        boolean hat = player != null && player.isModelPartShown(PlayerModelPart.HAT);
        int score = 0;
        Component formatted = null;
        int scoreWidth = 0;
        if (objective != null) {
            ReadOnlyScoreInfo scoreInfo = scoreboard.getPlayerScoreInfo(ScoreHolder.fromGameProfile(profile), objective);
            if (scoreInfo != null) {
                score = scoreInfo.value();
            }
            if (!hearts) {
                formatted = ReadOnlyScoreInfo.safeFormatValue(scoreInfo, objective.numberFormatOrDefault(StyledFormat.PLAYER_LIST_DEFAULT));
                scoreWidth = font.width(formatted);
            }
        }
        return new Row(overlay.getNameForDisplay(info), info.getSkin().texture(), hat, upsideDown, info.getLatency(),
                info.getGameMode() == GameType.SPECTATOR, score, formatted, scoreWidth);
    }

    private static List<Row> simulatedRows(List<FakePlayersPayload.Entry> fakes, @Nullable Objective objective, boolean hearts, Font font, long now) {
        long slot = now / 100L;
        if (fakes == cachedSource && slot == cachedSlot && objective == cachedObjective) {
            return cachedRows;
        }
        Component zero = objective != null && !hearts
                ? ReadOnlyScoreInfo.safeFormatValue(null, objective.numberFormatOrDefault(StyledFormat.PLAYER_LIST_DEFAULT))
                : null;
        int zeroWidth = zero == null ? 0 : font.width(zero);
        float flashing = ClientLimits.flashing();
        List<Row> rows = new ArrayList<>(Math.min(MAX_ENTRIES, fakes.size()));
        for (FakePlayersPayload.Entry entry : fakes) {
            if ((entry.flags() & 2) != 0 || unstable(entry, now / 150L, 11L, 0.010F * flashing)) {
                continue;
            }
            boolean corrupted = (entry.flags() & 1) != 0;
            Component name = corrupted
                    ? PlayerLikeRenderer.glitchName(entry.name(), entry.id().getLeastSignificantBits(), now)
                    : Component.literal(entry.name());
            int latency = unstable(entry, now / 250L, 37L, 0.008F + 0.02F * flashing) ? -1 : entry.latency();
            ResourceLocation face = corrupted && unstable(entry, now / 200L, 53L, 0.04F * Math.max(0.25F, flashing))
                    ? SkinSet.texture(SkinSet.firstOf(SkinSet.Category.MISSING))
                    : SkinSet.texture(entry.skin());
            rows.add(new Row(name, face, true, false, latency, false, 0, zero, zeroWidth));
        }
        cachedSource = fakes;
        cachedSlot = slot;
        cachedObjective = objective;
        cachedRows = rows;
        return rows;
    }

    /** A rare, per-entry, per-time-slot coin flip. */
    private static boolean unstable(FakePlayersPayload.Entry entry, long slot, long salt, float chance) {
        if (chance <= 0.0F) {
            return false;
        }
        long h = entry.id().getMostSignificantBits() ^ entry.id().getLeastSignificantBits() * 31L ^ salt * 0xD1B54A32D192ED03L
                ^ slot * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h & 0xFFFFL) < (long) (chance * 65536.0F);
    }

    private static Component header(Minecraft minecraft, long now) {
        String name = null;
        ServerData server = minecraft.getCurrentServer();
        MinecraftServer local = minecraft.getSingleplayerServer();
        if (server != null && server.name != null && !server.name.isBlank()) {
            name = server.name;
        } else if (local != null) {
            name = local.getWorldData().getLevelName();
        }
        if (name == null || name.isBlank()) {
            return Component.translatable("null.tablist.header");
        }
        long slot = now / 3000L;
        long h = slot * 0x9E3779B97F4A7C15L ^ name.hashCode();
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        if ((h & 63L) < 4L) {
            // Now and then the name does not come through.
            MutableComponent broken = PlayerLikeRenderer.glitchName(name, h, now);
            return broken;
        }
        return Component.literal(name);
    }

    // ------------------------------------------------------------------ drawing (vanilla layout)

    private static void draw(GuiGraphics graphics, Minecraft minecraft, ClientPacketListener connection, Font font, List<Row> rows,
                             @Nullable Objective objective, boolean hearts, Component header, Component footer) {
        int width = graphics.guiWidth();
        int space = font.width(" ");
        int nameWidth = 0;
        int scoreColumn = 0;
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, font.width(row.name()));
            if (objective != null && !hearts) {
                scoreColumn = Math.max(scoreColumn, row.scoreWidth() > 0 ? space + row.scoreWidth() : 0);
            }
        }
        int count = rows.size();
        int perColumn = count;
        int columns = 1;
        while (perColumn > 20) {
            columns++;
            perColumn = (count + columns - 1) / columns;
        }
        boolean heads = minecraft.isLocalServer() || connection.getConnection().isEncrypted();
        int scoreArea = objective == null ? 0 : hearts ? 90 : scoreColumn;
        int cellWidth = Math.min(columns * ((heads ? 9 : 0) + nameWidth + scoreArea + 13), width - 50) / columns;
        int left = width / 2 - (cellWidth * columns + (columns - 1) * 5) / 2;
        int y = 10;
        int boxWidth = cellWidth * columns + (columns - 1) * 5;
        List<FormattedCharSequence> headerLines = font.split(header, width - 50);
        for (FormattedCharSequence line : headerLines) {
            boxWidth = Math.max(boxWidth, font.width(line));
        }
        List<FormattedCharSequence> footerLines = font.split(footer, width - 50);
        for (FormattedCharSequence line : footerLines) {
            boxWidth = Math.max(boxWidth, font.width(line));
        }

        if (!headerLines.isEmpty()) {
            graphics.fill(width / 2 - boxWidth / 2 - 1, y - 1, width / 2 + boxWidth / 2 + 1, y + headerLines.size() * 9, Integer.MIN_VALUE);
            for (FormattedCharSequence line : headerLines) {
                int lineWidth = font.width(line);
                graphics.drawString(font, line, width / 2 - lineWidth / 2, y, -1);
                y += 9;
            }
            y++;
        }

        graphics.fill(width / 2 - boxWidth / 2 - 1, y - 1, width / 2 + boxWidth / 2 + 1, y + perColumn * 9, Integer.MIN_VALUE);
        int background = minecraft.options.getBackgroundColor(553648127);
        for (int i = 0; i < count; i++) {
            int column = i / perColumn;
            int line = i % perColumn;
            int x = left + column * cellWidth + column * 5;
            int rowY = y + line * 9;
            graphics.fill(x, rowY, x + cellWidth, rowY + 8, background);
            RenderSystem.enableBlend();
            Row row = rows.get(i);
            if (heads) {
                if (row.face() != null) {
                    PlayerFaceRenderer.draw(graphics, row.face(), x, rowY, 8, row.hat(), row.upsideDown());
                }
                x += 9;
            }
            graphics.drawString(font, row.name(), x, rowY, row.spectator() ? SPECTATOR_COLOR : -1);
            if (objective != null && !row.spectator()) {
                int scoreLeft = x + nameWidth + 1;
                int scoreRight = scoreLeft + scoreArea;
                if (scoreRight - scoreLeft > 5) {
                    if (hearts) {
                        drawHearts(graphics, font, rowY, scoreLeft, scoreRight, row.score());
                    } else if (row.formattedScore() != null) {
                        graphics.drawString(font, row.formattedScore(), scoreRight - row.scoreWidth(), rowY, 16777215);
                    }
                }
            }
            drawPing(graphics, cellWidth, x - (heads ? 9 : 0), rowY, row.latency());
        }

        if (!footerLines.isEmpty()) {
            y += perColumn * 9 + 1;
            graphics.fill(width / 2 - boxWidth / 2 - 1, y - 1, width / 2 + boxWidth / 2 + 1, y + footerLines.size() * 9, Integer.MIN_VALUE);
            for (FormattedCharSequence line : footerLines) {
                int lineWidth = font.width(line);
                graphics.drawString(font, line, width / 2 - lineWidth / 2, y, -1);
                y += 9;
            }
        }
    }

    private static void drawPing(GuiGraphics graphics, int width, int x, int y, int latency) {
        ResourceLocation sprite;
        if (latency < 0) {
            sprite = PING_UNKNOWN;
        } else if (latency < 150) {
            sprite = PING_5;
        } else if (latency < 300) {
            sprite = PING_4;
        } else if (latency < 600) {
            sprite = PING_3;
        } else if (latency < 1000) {
            sprite = PING_2;
        } else {
            sprite = PING_1;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 100.0F);
        graphics.blitSprite(sprite, x + width - 11, y, 10, 8);
        graphics.pose().popPose();
    }

    /** Vanilla's health display for a hearts objective, without the blink animation. */
    private static void drawHearts(GuiGraphics graphics, Font font, int y, int left, int right, int health) {
        int full = Mth.positiveCeilDiv(Math.max(health, 0), 2);
        int slots = Math.max(health, 20) / 2;
        if (full <= 0) {
            return;
        }
        int step = Mth.floor(Math.min((float) (right - left - 4) / (float) slots, 9.0F));
        if (step <= 3) {
            float fraction = Mth.clamp((float) health / 20.0F, 0.0F, 1.0F);
            int color = (int) ((1.0F - fraction) * 255.0F) << 16 | (int) (fraction * 255.0F) << 8;
            float value = (float) health / 2.0F;
            Component text = Component.translatable("multiplayer.player.list.hp", value);
            Component shown = right - font.width(text) >= left ? text : Component.literal(Float.toString(value));
            graphics.drawString(font, shown, (right + left - font.width(shown)) / 2, y, color);
            return;
        }
        for (int i = full; i < slots; i++) {
            graphics.blitSprite(HEART_CONTAINER, left + i * step, y, 9, 9);
        }
        for (int i = 0; i < full; i++) {
            graphics.blitSprite(HEART_CONTAINER, left + i * step, y, 9, 9);
            if (i * 2 + 1 < health) {
                graphics.blitSprite(i >= 10 ? HEART_ABSORBING_FULL : HEART_FULL, left + i * step, y, 9, 9);
            }
            if (i * 2 + 1 == health) {
                graphics.blitSprite(i >= 10 ? HEART_ABSORBING_HALF : HEART_HALF, left + i * step, y, 9, 9);
            }
        }
    }
}
