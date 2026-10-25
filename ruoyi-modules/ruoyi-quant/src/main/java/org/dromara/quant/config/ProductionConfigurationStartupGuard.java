package org.dromara.quant.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses production startup when critical security or infrastructure settings
 * are still empty placeholders. Reports property names only, never their values.
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
public class ProductionConfigurationStartupGuard implements ApplicationRunner {
    private static final List<String> REQUIRED_PROPERTIES = List.of(
        "quant.security.secret-key",
        "sa-token.jwt-secret-key",
        "api-decrypt.privateKey",
        "mybatis-encryptor.password",
        "spring.datasource.dynamic.datasource.master.url",
        "spring.datasource.dynamic.datasource.master.username",
        "spring.datasource.dynamic.datasource.master.password",
        "spring.data.redis.host",
        "spring.data.redis.port",
        "spring.data.redis.password"
    );

    private final Environment environment;

    public ProductionConfigurationStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    void verify() {
        List<String> missing = new ArrayList<>();
        for (String property : REQUIRED_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value == null || value.isBlank()) {
                missing.add(property);
            }
        }
        String datasourceUrl = environment.getProperty("spring.datasource.dynamic.datasource.master.url", "");
        if (datasourceUrl.contains("localhost") || datasourceUrl.contains("127.0.0.1")) {
            missing.add("spring.datasource.dynamic.datasource.master.url (must not target loopback in production)");
        }
        String redisHost = environment.getProperty("spring.data.redis.host", "");
        if ("localhost".equalsIgnoreCase(redisHost) || "127.0.0.1".equals(redisHost)) {
            missing.add("spring.data.redis.host (must not target loopback in production)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Production configuration preflight failed; configure required external properties: "
                + String.join(", ", missing) + ". Values are intentionally not logged.");
        }
    }
}
