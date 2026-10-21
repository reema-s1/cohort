package dev.cohort.server.flag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cohort.common.Rule;
import dev.cohort.common.RuleType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Embeddable
public class RuleEmbeddable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private int priority;

    @Enumerated(EnumType.STRING)
    private RuleType type;

    // Held as a JSON string so Hibernate's dirty check is a plain string compare, not a deep
    // compare of a mutable Map.
    @JdbcTypeCode(SqlTypes.JSON)
    private String condition;

    private String variant;

    protected RuleEmbeddable() {
    }

    static RuleEmbeddable of(Rule rule) {
        RuleEmbeddable embeddable = new RuleEmbeddable();
        embeddable.priority = rule.priority();
        embeddable.type = rule.type();
        embeddable.variant = rule.variant();
        try {
            embeddable.condition = JSON.writeValueAsString(rule.condition());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("rule condition is not serialisable", e);
        }
        return embeddable;
    }

    Rule toRule() {
        try {
            return new Rule(priority, type, JSON.readValue(condition, MAP), variant);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt rule condition: " + condition, e);
        }
    }
}
