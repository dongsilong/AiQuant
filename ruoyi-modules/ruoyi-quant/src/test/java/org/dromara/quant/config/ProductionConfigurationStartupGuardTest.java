package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class ProductionConfigurationStartupGuardTest {
    @Test
    void rejectsMissingSecretsAndLoopbackInfrastructureWithoutPrintingValues() {
        var environment = new StandardEnvironment();
        var values = new HashMap<String, Object>();
        values.put("quant.security.secret-key", "");
        values.put("sa-token.jwt-secret-key", "");
        values.put("api-decrypt.privateKey", "");
        values.put("mybatis-encryptor.password", "");
        values.put("spring.datasource.dynamic.datasource.master.url", "jdbc:mysql://localhost:3306/quantdb");
        values.put("spring.datasource.dynamic.datasource.master.username", "");
        values.put("spring.datasource.dynamic.datasource.master.password", "");
        values.put("spring.data.redis.host", "127.0.0.1");
        values.put("spring.data.redis.port", "");
        values.put("spring.data.redis.password", "");
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));

        var failure = assertThrows(IllegalStateException.class,
            () -> new ProductionConfigurationStartupGuard(environment).verify());

        assertTrue(failure.getMessage().contains("quant.security.secret-key"));
        assertTrue(failure.getMessage().contains("spring.datasource.dynamic.datasource.master.url"));
        assertTrue(failure.getMessage().contains("spring.data.redis.host"));
        assertTrue(!failure.getMessage().contains("jdbc:mysql://localhost"));
    }

    @Test
    void acceptsExternallyConfiguredProductionSecretsAndNetworkEndpoints() {
        var environment = new StandardEnvironment();
        Map<String, Object> values = Map.of(
            "quant.security.secret-key", "configured",
            "sa-token.jwt-secret-key", "configured",
            "api-decrypt.privateKey", "configured",
            "mybatis-encryptor.password", "configured",
            "spring.datasource.dynamic.datasource.master.url", "jdbc:mysql://db.internal:3306/quantdb",
            "spring.datasource.dynamic.datasource.master.username", "quant_app",
            "spring.datasource.dynamic.datasource.master.password", "configured",
            "spring.data.redis.host", "redis.internal",
            "spring.data.redis.port", "6379",
            "spring.data.redis.password", "configured"
        );
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));

        assertDoesNotThrow(() -> new ProductionConfigurationStartupGuard(environment).verify());
    }
}
