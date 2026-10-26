-- A flag's slice [layer_start, layer_end) of a mutually exclusive layer, or all three null.
-- Overlap between flags in one layer is checked by the service, under the config_version lock.
ALTER TABLE flags
    ADD COLUMN layer       VARCHAR(100),
    ADD COLUMN layer_start INT,
    ADD COLUMN layer_end   INT,
    ADD CONSTRAINT flags_layer_complete CHECK (
        (layer IS NULL AND layer_start IS NULL AND layer_end IS NULL)
        OR (layer IS NOT NULL AND layer_start >= 0 AND layer_start < layer_end AND layer_end <= 10000));

CREATE INDEX flags_layer ON flags (layer) WHERE layer IS NOT NULL;
