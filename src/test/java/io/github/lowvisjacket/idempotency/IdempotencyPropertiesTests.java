package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings the application can change. Binding is tested with Boot's own {@link Binder} (no Spring context),
 * so these tests are fast and show exactly what a line in application.properties does.
 */
class IdempotencyPropertiesTests {

    private static IdempotencyProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(IdempotencyProperties.PREFIX, IdempotencyProperties.class)
                .orElseGet(IdempotencyProperties::new);
    }

    @Test
    void prefixIsIdempotency() {
        assertEquals("idempotency", IdempotencyProperties.PREFIX);
    }

    @Test
    void defaultsWhenNothingIsConfigured() {
        IdempotencyProperties p = bind(Map.of());
        assertTrue(p.isEnabled());
        assertEquals(IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES, p.getMaxResponseBodyBytes());
    }

    @Test
    void newInstanceHasTheSameDefaults() {
        IdempotencyProperties p = new IdempotencyProperties();
        assertTrue(p.isEnabled());
        assertEquals(IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES, p.getMaxResponseBodyBytes());
    }

    @Test
    void enabledCanBeSwitchedOff() {
        assertFalse(bind(Map.of("idempotency.enabled", "false")).isEnabled());
    }

    @Test
    void maxResponseBodyBytesCanBeChanged() {
        assertEquals(1234, bind(Map.of("idempotency.max-response-body-bytes", "1234")).getMaxResponseBodyBytes());
    }

    @Test
    void camelCaseNameAlsoBinds() {
        assertEquals(77, bind(Map.of("idempotency.maxResponseBodyBytes", "77")).getMaxResponseBodyBytes());
    }

    @Test
    void settingsDoNotInfluenceEachOther() {
        IdempotencyProperties p = bind(Map.of("idempotency.enabled", "false"));
        assertEquals(IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES, p.getMaxResponseBodyBytes());
        IdempotencyProperties q = bind(Map.of("idempotency.max-response-body-bytes", "5"));
        assertTrue(q.isEnabled());
    }

    @Test
    void zeroIsAllowed() {
        assertEquals(0, bind(Map.of("idempotency.max-response-body-bytes", "0")).getMaxResponseBodyBytes());
    }

    @Test
    void otherPrefixesAreIgnored() {
        IdempotencyProperties p = bind(Map.of("other.enabled", "false", "idempotency-x.enabled", "false"));
        assertTrue(p.isEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-65536", "-2147483648"})
    void negativeSizeIsRejectedWhileBinding(String value) {
        assertThrows(BindException.class,
                () -> bind(Map.of("idempotency.max-response-body-bytes", value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "64k", "9999999999"})
    void nonNumberSizeIsRejectedWhileBinding(String value) {
        assertThrows(BindException.class, () -> bind(Map.of("idempotency.max-response-body-bytes", value)));
    }

    @Test
    void negativeSizeIsRejectedByTheSetterToo() {
        IdempotencyProperties p = new IdempotencyProperties();
        assertThrows(IllegalArgumentException.class, () -> p.setMaxResponseBodyBytes(-1));
        assertEquals(IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES, p.getMaxResponseBodyBytes(),
                "a rejected value must not change the setting");
    }

    @Test
    void settersWork() {
        IdempotencyProperties p = new IdempotencyProperties();
        p.setEnabled(false);
        p.setMaxResponseBodyBytes(9);
        assertFalse(p.isEnabled());
        assertEquals(9, p.getMaxResponseBodyBytes());
    }

    @Test
    void classIsMarkedAsConfigurationProperties() {
        ConfigurationProperties annotation = IdempotencyProperties.class.getAnnotation(ConfigurationProperties.class);
        assertNotNull(annotation, "add @ConfigurationProperties(prefix = PREFIX)");
        assertEquals("idempotency", annotation.prefix());
    }

    @Test
    void metadataFileDescribesEveryPropertyForTheIde() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertNotNull(in, "the annotation processor did not write the metadata file: is @ConfigurationProperties on the class?");
            JsonNode properties = JsonMapper.builder().build().readTree(in).get("properties");

            for (String name : new String[]{"idempotency.enabled", "idempotency.max-response-body-bytes"}) {
                JsonNode found = null;
                for (JsonNode p : properties) {
                    if (name.equals(p.get("name").asString())) found = p;
                }
                assertNotNull(found, name + " is missing from the metadata");
                assertTrue(found.hasNonNull("description") && !found.get("description").asString().isBlank(),
                        name + " needs a Javadoc sentence on its field (it becomes the IDE description)");
            }
        }
    }
}
