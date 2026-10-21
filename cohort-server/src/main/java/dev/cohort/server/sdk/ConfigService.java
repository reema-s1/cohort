package dev.cohort.server.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.cohort.common.FlagConfig;
import dev.cohort.common.FlagDefinition;
import dev.cohort.server.flag.ConfigVersionRepository;
import dev.cohort.server.flag.FlagChangedEvent;
import dev.cohort.server.flag.FlagEntity;
import dev.cohort.server.flag.FlagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Serves the full flag config from memory so {@code /sdk/v1/config} never hits Postgres per
 * request. The snapshot is rebuilt after every committed flag change on this instance, and expires
 * after 30 s as a safety net for changes made through another server instance.
 */
@Service
public class ConfigService {

    /** The config plus its pre-serialised JSON body and ETag, built once per version. */
    public record Snapshot(FlagConfig config, byte[] json, String etag) {
        public long version() {
            return config.version();
        }
    }

    private static final String KEY = "config";

    private final FlagRepository flags;
    private final ConfigVersionRepository configVersion;
    private final ObjectMapper mapper;
    private final TransactionTemplate readSnapshot;
    private final LoadingCache<String, Snapshot> cache;

    public ConfigService(FlagRepository flags, ConfigVersionRepository configVersion, ObjectMapper mapper,
                         PlatformTransactionManager transactions) {
        this.flags = flags;
        this.configVersion = configVersion;
        this.mapper = mapper;
        // REPEATABLE READ: the flags and the version number come from the same database snapshot,
        // so a config can never be labelled with a version it does not contain.
        this.readSnapshot = new TransactionTemplate(transactions);
        this.readSnapshot.setReadOnly(true);
        this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build(key -> load());
    }

    public Snapshot current() {
        return cache.get(KEY);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFlagChanged(FlagChangedEvent event) {
        cache.put(KEY, load());
    }

    private Snapshot load() {
        FlagConfig config = readSnapshot.execute(status -> {
            long version = configVersion.current();
            Map<String, FlagDefinition> definitions = flags.findByArchivedFalseOrderByKey().stream()
                    .map(FlagEntity::toDefinition)
                    .collect(Collectors.toMap(FlagDefinition::key, Function.identity()));
            return new FlagConfig(version, definitions);
        });
        try {
            return new Snapshot(config, mapper.writeValueAsBytes(config), "\"" + config.version() + "\"");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise flag config", e);
        }
    }
}
