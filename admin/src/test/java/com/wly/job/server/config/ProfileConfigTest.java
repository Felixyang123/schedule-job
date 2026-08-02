package com.wly.job.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileConfigTest {

    private Map<String, Object> load(String file) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(file, new ClassPathResource(file));
        Map<String, Object> props = new HashMap<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    props.put(name, enumerable.getProperty(name));
                }
            }
        }
        return props;
    }

    @Test
    void devProfileContainsBusinessConfig() throws Exception {
        Map<String, Object> props = load("application-dev.yml");
        assertTrue(props.containsKey("schedule.registry"));
        assertTrue(props.containsKey("schedule.dispatch-threads"));
        assertTrue(props.containsKey("spring.datasource.url"));
        assertTrue(props.containsKey("instance-client.serverAddress"));
    }

    @Test
    void prodProfileUsesEnvPlaceholdersWithoutDefaults() throws Exception {
        Map<String, Object> props = load("application-prod.yml");
        assertEquals("${DB_URL}", props.get("spring.datasource.url"));
        assertEquals("${DB_USERNAME}", props.get("spring.datasource.username"));
        assertEquals("${DB_PASSWORD}", props.get("spring.datasource.password"));
        assertTrue(props.containsKey("schedule.dispatch-threads"));
    }

    @Test
    void commonProfileHasNoSecrets() throws Exception {
        Map<String, Object> props = load("application.yml");
        assertTrue(props.containsKey("spring.profiles.active"));
        assertTrue(!props.containsKey("spring.datasource.password"));
    }
}
