package dev.cohort.server.flag;

import dev.cohort.common.Layer;
import dev.cohort.common.Rule;
import dev.cohort.common.Variant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public final class FlagDtos {

    private FlagDtos() {
    }

    /**
     * @param salt optional; a random salt is generated when omitted. It cannot be changed later,
     *             because changing it would reshuffle every user.
     * @param layer optional slice of a mutually exclusive layer; it must not overlap the slice of
     *              any other live flag in the same layer
     */
    public record CreateFlagRequest(
            @NotBlank String key,
            String description,
            Boolean enabled,
            String salt,
            @NotBlank String defaultVariant,
            @NotEmpty List<Variant> variants,
            List<Rule> rules,
            Layer layer) {

        public CreateFlagRequest(String key, String description, Boolean enabled, String salt, String defaultVariant,
                                 List<Variant> variants, List<Rule> rules) {
            this(key, description, enabled, salt, defaultVariant, variants, rules, null);
        }
    }

    /** Full replacement of a flag's mutable fields. {@code version} must match the stored version. */
    public record UpdateFlagRequest(
            String description,
            @NotNull Boolean enabled,
            @NotBlank String defaultVariant,
            @NotEmpty List<Variant> variants,
            List<Rule> rules,
            @NotNull Long version,
            Layer layer) {

        public UpdateFlagRequest(String description, Boolean enabled, String defaultVariant, List<Variant> variants,
                                 List<Rule> rules, Long version) {
            this(description, enabled, defaultVariant, variants, rules, version, null);
        }
    }

    public record FlagResponse(
            String key,
            String description,
            boolean enabled,
            boolean archived,
            String salt,
            String defaultVariant,
            long version,
            List<Variant> variants,
            List<Rule> rules,
            Layer layer,
            Instant createdAt,
            Instant updatedAt) {

        static FlagResponse of(FlagEntity entity) {
            var definition = entity.toDefinition();
            return new FlagResponse(
                    entity.getKey(),
                    entity.getDescription(),
                    entity.isEnabled(),
                    entity.isArchived(),
                    entity.getSalt(),
                    entity.getDefaultVariant(),
                    entity.getVersion(),
                    definition.variants(),
                    definition.rules(),
                    definition.layer(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }
}
