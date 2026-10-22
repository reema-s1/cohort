package dev.cohort.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cohort.common.FlagConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current config as an immutable snapshot behind an {@link AtomicReference}. Readers
 * never lock; the poller swaps in a whole new snapshot. The last good config is also written to
 * disk so a restart during a server outage still has flags.
 */
final class ConfigStore {

    enum Source { NONE, SERVER, DISK }

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final AtomicReference<FlagConfig> current = new AtomicReference<>();
    private final Path snapshotFile;
    private final ObjectMapper mapper;
    private volatile Source source = Source.NONE;

    ConfigStore(Path snapshotFile, ObjectMapper mapper) {
        this.snapshotFile = snapshotFile;
        this.mapper = mapper;
    }

    /** May be null before the first successful fetch or disk load. */
    FlagConfig get() {
        return current.get();
    }

    Source source() {
        return source;
    }

    long version() {
        FlagConfig config = current.get();
        return config == null ? -1 : config.version();
    }

    /**
     * Swaps in a config from the server. Older versions are ignored, so a lagging server instance
     * behind a load balancer can never roll an SDK back.
     */
    boolean acceptFromServer(FlagConfig next) {
        while (true) {
            FlagConfig previous = current.get();
            if (previous != null && next.version() < previous.version()) {
                return false;
            }
            if (current.compareAndSet(previous, next)) {
                source = Source.SERVER;
                writeToDisk(next);
                return true;
            }
        }
    }

    /** Loads the disk snapshot, but only if nothing fresher is already in memory. */
    boolean loadFromDisk() {
        if (snapshotFile == null || !Files.exists(snapshotFile)) {
            return false;
        }
        try {
            FlagConfig fromDisk = mapper.readValue(snapshotFile.toFile(), FlagConfig.class);
            if (current.compareAndSet(null, fromDisk)) {
                source = Source.DISK;
                log.warn("Cohort server unreachable; serving flags from disk snapshot (config version {})", fromDisk.version());
                return true;
            }
            return false;
        } catch (IOException e) {
            log.warn("Cohort disk snapshot {} is unreadable; ignoring it", snapshotFile, e);
            return false;
        }
    }

    private void writeToDisk(FlagConfig config) {
        if (snapshotFile == null) {
            return;
        }
        try {
            Path dir = snapshotFile.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            // Write then rename, so a crash mid-write never leaves a half-written snapshot.
            Path temp = Files.createTempFile(dir, "cohort-snapshot", ".tmp");
            mapper.writeValue(temp.toFile(), config);
            try {
                Files.move(temp, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not write Cohort disk snapshot to {}", snapshotFile, e);
        }
    }
}
