package dev.cohort.server.flag;

import dev.cohort.common.Variant;
import jakarta.persistence.Embeddable;

@Embeddable
public class VariantEmbeddable {

    private String name;

    private int weight;

    protected VariantEmbeddable() {
    }

    static VariantEmbeddable of(Variant variant) {
        VariantEmbeddable embeddable = new VariantEmbeddable();
        embeddable.name = variant.name();
        embeddable.weight = variant.weight();
        return embeddable;
    }

    Variant toVariant() {
        return new Variant(name, weight);
    }
}
