package dev.kristian.nullmod.network;

import dev.kristian.nullmod.NullMod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the full list of simulated players for the player list. These are not
 * connections and not real accounts; they only exist inside NULL's simulation.
 */
public record FakePlayersPayload(List<Entry> entries) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 256;
    public static final Type<FakePlayersPayload> TYPE = new Type<>(NullMod.id("fake_players"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FakePlayersPayload> STREAM_CODEC =
            StreamCodec.ofMember(FakePlayersPayload::write, FakePlayersPayload::read);

    /**
     * @param skin    index into NULL's skin set
     * @param latency fake ping in milliseconds (negative shows the "unknown" bars)
     * @param flags   bit 0: name shown corrupted, bit 1: hidden from the list this moment
     */
    public record Entry(UUID id, String name, int skin, int latency, int flags) {
    }

    private static FakePlayersPayload read(RegistryFriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readUUID(), buf.readUtf(32), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new FakePlayersPayload(List.copyOf(entries));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        int size = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Entry e = entries.get(i);
            buf.writeUUID(e.id());
            buf.writeUtf(e.name(), 32);
            buf.writeVarInt(e.skin());
            buf.writeVarInt(e.latency());
            buf.writeVarInt(e.flags());
        }
    }

    @Override
    public Type<FakePlayersPayload> type() {
        return TYPE;
    }
}
