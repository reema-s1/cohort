package dev.cohort.server.flag;

import dev.cohort.common.FlagDefinition;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "flags")
public class FlagEntity {

    @Id
    private String key;

    private String description;

    private boolean enabled;

    private String salt;

    private String defaultVariant;

    /**
     * Managed by hand rather than with JPA's @Version: Hibernate force-increments @Version when
     * element collections are first written, so the version a create returned was already stale.
     */
    private long version;

    private boolean archived;

    private Instant createdAt;

    private Instant updatedAt;

    // SUBSELECT: loading every flag for the SDK config is three queries, not 1 + 2N.
    @ElementCollection
    @CollectionTable(name = "variants", joinColumns = @JoinColumn(name = "flag_key"))
    @OrderColumn(name = "position")
    @Fetch(FetchMode.SUBSELECT)
    private List<VariantEmbeddable> variants = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "rules", joinColumns = @JoinColumn(name = "flag_key"))
    @OrderColumn(name = "position")
    @Fetch(FetchMode.SUBSELECT)
    private List<RuleEmbeddable> rules = new ArrayList<>();

    protected FlagEntity() {
    }

    public FlagEntity(String key, String salt) {
        this.key = key;
        this.salt = salt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public FlagDefinition toDefinition() {
        return new FlagDefinition(
                key,
                enabled,
                archived,
                salt,
                defaultVariant,
                version,
                variants.stream().map(VariantEmbeddable::toVariant).toList(),
                rules.stream().map(RuleEmbeddable::toRule).toList());
    }

    public void replaceVariants(List<Variant> next) {
        variants.clear();
        next.forEach(v -> variants.add(VariantEmbeddable.of(v)));
    }

    public void replaceRules(List<Rule> next) {
        rules.clear();
        next.forEach(r -> rules.add(RuleEmbeddable.of(r)));
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSalt() {
        return salt;
    }

    public String getDefaultVariant() {
        return defaultVariant;
    }

    public void setDefaultVariant(String defaultVariant) {
        this.defaultVariant = defaultVariant;
    }

    public long getVersion() {
        return version;
    }

    public void incrementVersion() {
        version++;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
