package dev.cohort.server.flag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cohort.common.FlagDefinition;
import dev.cohort.common.FlagValidator;
import dev.cohort.server.audit.AuditEntry;
import dev.cohort.server.audit.AuditRepository;
import dev.cohort.server.flag.FlagDtos.CreateFlagRequest;
import dev.cohort.server.flag.FlagDtos.FlagResponse;
import dev.cohort.server.flag.FlagDtos.UpdateFlagRequest;
import dev.cohort.server.flag.FlagExceptions.FlagConflictException;
import dev.cohort.server.flag.FlagExceptions.FlagNotFoundException;
import dev.cohort.server.flag.FlagExceptions.InvalidFlagException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every write runs in one transaction that changes the flag, appends to the audit log and bumps
 * the global config version, so none of the three can disagree.
 */
@Service
public class FlagService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FlagRepository flags;
    private final AuditRepository audit;
    private final ConfigVersionRepository configVersion;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public FlagService(FlagRepository flags, AuditRepository audit, ConfigVersionRepository configVersion,
                       ApplicationEventPublisher events, ObjectMapper mapper) {
        this.flags = flags;
        this.audit = audit;
        this.configVersion = configVersion;
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<FlagResponse> list(boolean includeArchived) {
        var entities = includeArchived ? flags.findAllByOrderByKey() : flags.findByArchivedFalseOrderByKey();
        return entities.stream().map(FlagResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public FlagResponse get(String key) {
        return FlagResponse.of(load(key));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> auditLog(String key) {
        load(key);
        return audit.findByFlagKeyOrderByIdDesc(key);
    }

    @Transactional
    public FlagResponse create(CreateFlagRequest request, String actor) {
        if (flags.existsById(request.key())) {
            throw new FlagConflictException("flag '" + request.key() + "' already exists");
        }
        String salt = request.salt() == null || request.salt().isBlank() ? newSalt() : request.salt();
        FlagEntity entity = new FlagEntity(request.key(), salt);
        entity.setDescription(request.description());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setDefaultVariant(request.defaultVariant());
        entity.replaceVariants(request.variants());
        entity.replaceRules(request.rules() == null ? List.of() : request.rules());
        validate(entity);

        // Assigned id, so Spring Data merges; use the managed copy it returns.
        entity = flags.saveAndFlush(entity);
        recordChange(entity, "CREATE", null, actor);
        return FlagResponse.of(entity);
    }

    @Transactional
    public FlagResponse update(String key, UpdateFlagRequest request, String actor) {
        FlagEntity entity = loadForUpdate(key);
        if (entity.isArchived()) {
            throw new FlagConflictException("flag '" + key + "' is archived");
        }
        // Optimistic locking at the API level: the client sends the version it read. The row lock
        // taken by loadForUpdate makes check-then-increment atomic, so two concurrent writers based
        // on the same version cannot both pass.
        if (!Objects.equals(request.version(), entity.getVersion())) {
            throw new FlagConflictException("stale write: flag '" + key + "' is at version "
                    + entity.getVersion() + ", request was based on " + request.version());
        }
        Map<String, Object> before = snapshot(entity);
        entity.setDescription(request.description());
        entity.setEnabled(request.enabled());
        entity.setDefaultVariant(request.defaultVariant());
        entity.replaceVariants(request.variants());
        entity.replaceRules(request.rules() == null ? List.of() : request.rules());
        validate(entity);

        entity.incrementVersion();
        recordChange(entity, "UPDATE", before, actor);
        return FlagResponse.of(entity);
    }

    /** Turns the flag off for everyone. SDKs pick it up on their next poll. Idempotent. */
    @Transactional
    public FlagResponse kill(String key, String actor) {
        FlagEntity entity = loadForUpdate(key);
        if (!entity.isEnabled()) {
            return FlagResponse.of(entity);
        }
        Map<String, Object> before = snapshot(entity);
        entity.setEnabled(false);
        entity.incrementVersion();
        recordChange(entity, "KILL", before, actor);
        return FlagResponse.of(entity);
    }

    /** Archived flags drop out of the SDK config, so SDKs fall back to the caller's default. */
    @Transactional
    public FlagResponse archive(String key, String actor) {
        FlagEntity entity = loadForUpdate(key);
        if (entity.isArchived()) {
            return FlagResponse.of(entity);
        }
        Map<String, Object> before = snapshot(entity);
        entity.setArchived(true);
        entity.setEnabled(false);
        entity.incrementVersion();
        recordChange(entity, "ARCHIVE", before, actor);
        return FlagResponse.of(entity);
    }

    private void recordChange(FlagEntity entity, String action, Map<String, Object> before, String actor) {
        audit.save(new AuditEntry(entity.getKey(), actor, action, before, snapshot(entity)));
        long version = configVersion.bump();
        events.publishEvent(new FlagChangedEvent(entity.getKey(), version));
    }

    private FlagEntity load(String key) {
        return flags.findById(key).orElseThrow(() -> new FlagNotFoundException(key));
    }

    private FlagEntity loadForUpdate(String key) {
        return flags.findForUpdate(key).orElseThrow(() -> new FlagNotFoundException(key));
    }

    private static void validate(FlagEntity entity) {
        List<String> errors = FlagValidator.validate(entity.toDefinition());
        if (!errors.isEmpty()) {
            throw new InvalidFlagException(errors);
        }
    }

    private Map<String, Object> snapshot(FlagEntity entity) {
        FlagDefinition definition = entity.toDefinition();
        Map<String, Object> snapshot = new LinkedHashMap<>(mapper.convertValue(definition, MAP));
        snapshot.put("description", entity.getDescription());
        return snapshot;
    }

    private static String newSalt() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
