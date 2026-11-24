package br.com.cshub.taxify.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Feign timeouts must sit under the prefix OpenFeign 4+ reads ("spring.cloud.openfeign.client.config") -
 * under the old "feign.client.config" they were silently ignored and SIEG ran on the 60s default.
 */
class FeignTimeoutsConfigTest {

    @Test
    void timeoutsAreUnderTheOpenFeignPrefix() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yaml"));
        PropertySource<?> yaml = sources.getFirst();

        assertThat(yaml.getProperty("spring.cloud.openfeign.client.config.siegClient.read-timeout")).hasToString("180000");
        assertThat(yaml.getProperty("spring.cloud.openfeign.client.config.acessoriasClient.read-timeout")).hasToString("60000");
        assertThat(yaml.getProperty("feign.client.config.siegClient.read-timeout")).isNull();
    }
}
