package org.dromara.quant.strategy;

import org.dromara.config.strategy.*;
import org.dromara.config.strategy.context.StrategyContext;
import org.dromara.config.strategy.metadata.StrategyDescriptor;
import org.dromara.config.strategy.metadata.StrategyParamSchema;
import org.dromara.config.strategy.signal.EntrySignal;
import org.dromara.config.strategy.signal.ExitSignal;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class StrategyPluginRegistryTest {
    @Test
    void legacyPluginIsOnlyAvailableForExistingPositionProtection() {
        StrategyPlugin plugin = new LegacyPlugin();
        StrategyPluginRegistry registry = new StrategyPluginRegistry(List.of(plugin));

        assertTrue(registry.descriptors().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("legacy"));
        assertSame(plugin, registry.findForProtection("legacy").orElseThrow());
    }

    @Test
    void validatesPluginParametersAgainstSchemaBeforePluginValidation() {
        StrategyPluginRegistry registry = new StrategyPluginRegistry(List.of(new SchemaPlugin()));

        assertDoesNotThrow(() -> registry.validate("schema", Map.of("count", 2)));
        assertThrows(IllegalArgumentException.class, () -> registry.validate("schema", Map.of("count", 4)));
        assertThrows(IllegalArgumentException.class, () -> registry.validate("schema", Map.of("count", "2")));
        assertThrows(IllegalArgumentException.class, () -> registry.validate("schema", Map.of("unknown", 1)));
    }

    private static final class SchemaPlugin implements StrategyPlugin {
        @Override public StrategyDescriptor descriptor() {
            return new StrategyDescriptor("schema", "1", "schema", Map.of(), List.of(
                new StrategyParamSchema("count", "Count", "integer", true, null, Map.of("min", 1, "max", 3), "")));
        }
        @Override public void validateConfig(Map<String, Object> params) { }
        @Override public Optional<EntrySignal> evaluateEntry(StrategyContext context) { return Optional.empty(); }
        @Override public List<ExitSignal> evaluateExit(StrategyContext context) { return List.of(); }
    }

    @LegacyStrategy
    private static final class LegacyPlugin implements StrategyPlugin {
        @Override public StrategyDescriptor descriptor() { return new StrategyDescriptor("legacy", "1", "legacy"); }
        @Override public void validateConfig(Map<String, Object> params) { }
        @Override public Optional<EntrySignal> evaluateEntry(StrategyContext context) { return Optional.empty(); }
        @Override public List<ExitSignal> evaluateExit(StrategyContext context) { return List.of(); }
    }
}
