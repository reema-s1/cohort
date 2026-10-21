package dev.cohort.server.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String flagKey;

    private String actor;

    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> after;

    @Column(name = "at")
    private Instant at;

    protected AuditEntry() {
    }

    public AuditEntry(String flagKey, String actor, String action, Map<String, Object> before, Map<String, Object> after) {
        this.flagKey = flagKey;
        this.actor = actor;
        this.action = action;
        this.before = before;
        this.after = after;
        this.at = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getFlagKey() {
        return flagKey;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public Instant getAt() {
        return at;
    }
}
