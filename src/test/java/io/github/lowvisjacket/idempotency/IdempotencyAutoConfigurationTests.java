package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyStoreAutoConfiguration;
import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestFilter;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.RedisIdempotencyStore;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import io.github.lowvisjacket.idempotency.web.ResponseCaptureFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the auto-configuration creates, when, and what the application can replace. Uses Boot's context runners:
 * a small throw-away Spring context per test, so no server and no Redis are started.
 */
class IdempotencyAutoConfigurationTests {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IdempotencyAutoConfiguration.class))
            .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class));

    private static FilterRegistrationBean<?> registrationFor(
            org.springframework.context.ApplicationContext context, Class<?> filterType) {
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(r -> filterType.isInstance(r.getFilter()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no filter registration for " + filterType.getSimpleName()));
    }

    // ---- what gets created

    @Test
    void createsEveryBeanInAServletApplication() {
        web.run(context -> {
            assertThat(context).hasSingleBean(Fingerprint.class);
            assertThat(context).hasSingleBean(ProblemResponseWriter.class);
            assertThat(context).hasSingleBean(IdempotencyInterceptor.class);
            assertThat(context).hasSingleBean(IdempotencyProperties.class);
            assertThat(context).hasSingleBean(WebMvcConfigurer.class);
            assertThat(context.getBeansOfType(FilterRegistrationBean.class)).hasSize(2);
        });
    }

    @Test
    void registersExactlyOneOfEachFilter() {
        web.run(context -> {
            long request = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                    .filter(r -> r.getFilter() instanceof RequestFilter).count();
            long response = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                    .filter(r -> r.getFilter() instanceof ResponseCaptureFilter).count();
            assertThat(request).isEqualTo(1);
            assertThat(response).isEqualTo(1);
        });
    }

    @Test
    void filtersRunAtTheDocumentedOrder() {
        web.run(context -> {
            assertThat(registrationFor(context, ResponseCaptureFilter.class).getOrder())
                    .isEqualTo(IdempotencyDefaults.RESPONSE_FILTER_ORDER);
            assertThat(registrationFor(context, RequestFilter.class).getOrder())
                    .isEqualTo(IdempotencyDefaults.REQUEST_FILTER_ORDER);
        });
    }

    @Test
    void filtersRunAfterSpringSecurityAndTheResponseFilterIsOuter() {
        web.run(context -> {
            int responseOrder = registrationFor(context, ResponseCaptureFilter.class).getOrder();
            int requestOrder = registrationFor(context, RequestFilter.class).getOrder();
            assertThat(responseOrder).as("after Spring Security (-100)").isGreaterThan(-100);
            assertThat(responseOrder).as("response filter wraps the request filter").isLessThan(requestOrder);
        });
    }

    @Test
    void filtersCoverEveryUrl() {
        web.run(context -> {
            for (Class<?> type : List.of(RequestFilter.class, ResponseCaptureFilter.class)) {
                FilterRegistrationBean<?> registration = registrationFor(context, type);
                assertThat(registration.isEnabled()).isTrue();
                // no patterns at all means "/*" in a FilterRegistrationBean; both spellings are fine
                List<String> patterns = List.copyOf(registration.getUrlPatterns());
                assertThat(patterns.isEmpty() || patterns.equals(List.of("/*"))).isTrue();
            }
        });
    }

    @Test
    void responseFilterUsesTheDefaultLimit() {
        web.run(context -> assertThat(ReflectionTestUtils.getField(
                registrationFor(context, ResponseCaptureFilter.class).getFilter(), "maxResponseBytes"))
                .isEqualTo(IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES));
    }

    @Test
    void responseFilterUsesTheConfiguredLimit() {
        web.withPropertyValues("idempotency.max-response-body-bytes=7").run(context ->
                assertThat(ReflectionTestUtils.getField(
                        registrationFor(context, ResponseCaptureFilter.class).getFilter(), "maxResponseBytes"))
                        .isEqualTo(7));
    }

    @Test
    void propertiesAreBoundFromTheEnvironment() {
        web.withPropertyValues("idempotency.max-response-body-bytes=321").run(context ->
                assertThat(context.getBean(IdempotencyProperties.class).getMaxResponseBodyBytes()).isEqualTo(321));
    }

    @Test
    void aBadPropertyStopsStartupInsteadOfFailingLater() {
        web.withPropertyValues("idempotency.max-response-body-bytes=-5").run(context ->
                assertThat(context).hasFailed());
    }

    // ---- when it stays out of the way

    @Test
    void createsNothingInANonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IdempotencyAutoConfiguration.class))
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotencyInterceptor.class);
                    assertThat(context).doesNotHaveBean(Fingerprint.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False"})
    void createsNothingWhenSwitchedOff(String value) {
        web.withPropertyValues("idempotency.enabled=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotencyInterceptor.class);
            assertThat(context).doesNotHaveBean(Fingerprint.class);
            assertThat(context).doesNotHaveBean(ProblemResponseWriter.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context).doesNotHaveBean(WebMvcConfigurer.class);
        });
    }

    @Test
    void explicitlyEnabledIsTheSameAsDefault() {
        web.withPropertyValues("idempotency.enabled=true").run(context ->
                assertThat(context).hasSingleBean(IdempotencyInterceptor.class));
    }

    @Test
    void backsOffQuietlyWithoutAStore() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IdempotencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotencyInterceptor.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    // ---- the application can replace anything

    @Test
    void applicationFingerprintWins() {
        Fingerprint mine = new Fingerprint();
        web.withBean("myFingerprint", Fingerprint.class, () -> mine).run(context -> {
            assertThat(context).hasSingleBean(Fingerprint.class);
            assertThat(context.getBean(Fingerprint.class)).isSameAs(mine);
        });
    }

    @Test
    void applicationProblemWriterWins() {
        ProblemResponseWriter mine = new ProblemResponseWriter(JsonMapper.builder().build());
        web.withBean("myWriter", ProblemResponseWriter.class, () -> mine).run(context -> {
            assertThat(context).hasSingleBean(ProblemResponseWriter.class);
            assertThat(context.getBean(ProblemResponseWriter.class)).isSameAs(mine);
        });
    }

    @Test
    void applicationInterceptorWins() {
        IdempotencyInterceptor mine = new IdempotencyInterceptor(
                mock(IdempotencyStore.class), new Fingerprint(), new ProblemResponseWriter(JsonMapper.builder().build()));
        web.withBean("myInterceptor", IdempotencyInterceptor.class, () -> mine).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyInterceptor.class);
            assertThat(context.getBean(IdempotencyInterceptor.class)).isSameAs(mine);
        });
    }

    @Test
    void theInterceptorUsesTheApplicationsStore() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IdempotencyAutoConfiguration.class))
                .withBean(IdempotencyStore.class, () -> mine)
                .run(context -> assertThat(ReflectionTestUtils.getField(
                        context.getBean(IdempotencyInterceptor.class), "store")).isSameAs(mine));
    }

    // ---- ordering between the two auto-configurations

    @Test
    void storeConfigurationIsProcessedBeforeTheMainOneWhateverTheListingOrder() {
        new WebApplicationContextRunner()
                // listed "wrong way round" on purpose: Boot must sort them by the "after" declarations
                .withConfiguration(AutoConfigurations.of(
                        IdempotencyAutoConfiguration.class,
                        IdempotencyStoreAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisIdempotencyStore.class);
                    assertThat(context).hasSingleBean(IdempotencyInterceptor.class);
                });
    }

    @Test
    void mainConfigurationDeclaresItComesAfterTheStoreOne() {
        AutoConfiguration declaration = IdempotencyAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
        assertThat(declaration).as("@AutoConfiguration is missing").isNotNull();
        assertThat(declaration.after()).contains(IdempotencyStoreAutoConfiguration.class);
    }

    // ---- registration with Spring Boot

    @Test
    void bothAutoConfigurationsAreListedInTheImportsFile() {
        List<String> listed = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();
        assertThat(listed).contains(
                IdempotencyAutoConfiguration.class.getName(),
                IdempotencyStoreAutoConfiguration.class.getName());
    }

    // ---- a library must not push beans into applications that scan its package

    @Test
    void libraryClassesAreNotComponentScannable() {
        for (Class<?> type : List.of(Fingerprint.class, RedisIdempotencyStore.class, RequestFilter.class,
                ResponseCaptureFilter.class, IdempotencyInterceptor.class, ProblemResponseWriter.class)) {
            assertThat(AnnotatedElementUtils.hasAnnotation(type, Component.class))
                    .as(type.getSimpleName() + " must not be @Component/@Service: only the auto-configuration creates it")
                    .isFalse();
        }
    }
}
