package dev.kristian.nullmod.client;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionStage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Remembers the highest stage this player has seen in any world, so the main menu can show
 * how far the corruption has gone. Reading it never changes any world.
 */
public final class ClientStageMemory {
    private static final String FILE = "null-client-state.properties";
    private static CorruptionStage highest;

    private ClientStageMemory() {
    }

    public static synchronized CorruptionStage highestStage() {
        if (highest == null) {
            highest = load();
        }
        return highest;
    }

    static synchronized void record(CorruptionStage stage) {
        CorruptionStage current = highestStage();
        if (stage.number() > current.number()) {
            highest = stage;
            save(stage);
        }
    }

    /** Forgets the remembered stage (from NULL's settings screen). */
    public static synchronized void forget() {
        highest = CorruptionStage.START;
        save(highest);
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static CorruptionStage load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return CorruptionStage.START;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return CorruptionStage.byNumber(Integer.parseInt(properties.getProperty("highestStage", "1").trim()));
        } catch (IOException | RuntimeException e) {
            // Properties.load also throws IllegalArgumentException for a damaged file
            NullMod.LOGGER.warn("Could not read {}", path, e);
            return CorruptionStage.START;
        }
    }

    private static void save(CorruptionStage stage) {
        Path path = path();
        Properties properties = new Properties();
        properties.setProperty("highestStage", Integer.toString(stage.number()));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "NULL - highest corruption stage seen on this computer");
            }
        } catch (IOException e) {
            NullMod.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
