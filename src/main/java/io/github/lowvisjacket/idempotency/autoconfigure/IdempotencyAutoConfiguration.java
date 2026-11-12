package io.github.lowvisjacket.idempotency.autoconfigure;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestFilter;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.web.IdempotentHandlerValidator;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import io.github.lowvisjacket.idempotency.web.ResponseCaptureFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the library into a Spring MVC application, so adding the starter to the classpath is all a user does.
 *
 * <p>What it creates (every bean can be replaced: if the application defines its own bean of the same type, the
 * library's is skipped):
 * <ul>
 *   <li>{@link Fingerprint}, {@link ProblemResponseWriter} and {@link IdempotencyInterceptor};</li>
 *   <li>the {@link RequestFilter} (so the body can be read twice) and the {@link ResponseCaptureFilter} (so the
 *       response can be saved), each registered as a servlet filter with the orders from
 *       {@code IdempotencyDefaults};</li>
 *   <li>a {@link WebMvcConfigurer} that adds the interceptor to Spring MVC for every path. The interceptor itself
 *       ignores endpoints without {@code @Idempotent}.</li>
 * </ul>
 *
 * <p>When it switches itself off (no error, simply no beans):
 * <ul>
 *   <li>the application is not a servlet web application;</li>
 *   <li>{@code idempotency.enabled=false};</li>
 *   <li>there is no {@link IdempotencyStore} bean (nothing to save to).</li>
 * </ul>
 *
 * <p><b>Order matters.</b> The store is created by {@link IdempotencyStoreAutoConfiguration}, and the "is there a
 * store?" check only works if that has already run, so this class must be processed after it.
 *
 * <p>The class is listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Without that file Boot never finds it.
 *
 * @see IdempotencyStoreAutoConfiguration
 * @see IdempotencyProperties
 */
@AutoConfiguration(after = IdempotencyStoreAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = IdempotencyProperties.PREFIX, name = "enabled", matchIfMissing = true)
@ConditionalOnBean(IdempotencyStore.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    /**
     * @return the shared fingerprint calculator (it holds no state)
     */
    @Bean
    @ConditionalOnMissingBean
    public Fingerprint fingerprint() {
        return new Fingerprint();
    }

    /**
     * @param objectMapper the application's Jackson mapper (Boot provides one)
     * @return the writer for {@code application/problem+json} refusals
     */
    @Bean
    @ConditionalOnMissingBean
    public ProblemResponseWriter problemResponseWriter(ObjectMapper objectMapper) {
        return new ProblemResponseWriter(objectMapper);
    }

    /**
     * @return the interceptor that claims keys, refuses repeats and replays saved responses
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore store, Fingerprint fingerprint,
                                                         ProblemResponseWriter writer) {
        return new IdempotencyInterceptor(store, fingerprint, writer);
    }

    /**
     * Registers the {@link RequestFilter} as a servlet filter.
     *
     * @return a registration for all URLs, running at {@code IdempotencyDefaults.REQUEST_FILTER_ORDER}
     */
    @Bean
    public FilterRegistrationBean<RequestFilter> requestFilterRegistration() {
        FilterRegistrationBean<RequestFilter> filterRegistrationBean = new FilterRegistrationBean<RequestFilter>(new RequestFilter());
        filterRegistrationBean.setOrder(IdempotencyDefaults.REQUEST_FILTER_ORDER);
        return filterRegistrationBean;
    }

    /**
     * Registers the {@link ResponseCaptureFilter}, holding at most {@code properties.getMaxResponseBodyBytes()} bytes.
     *
     * @return a registration for all URLs, running at {@code IdempotencyDefaults.RESPONSE_FILTER_ORDER}
     */
    @Bean
    public FilterRegistrationBean<ResponseCaptureFilter> responseCaptureFilterRegistration(
            IdempotencyStore store, IdempotencyProperties properties) {
        FilterRegistrationBean<ResponseCaptureFilter> filterRegistrationBean = new FilterRegistrationBean<ResponseCaptureFilter>(new ResponseCaptureFilter(store, properties.getMaxResponseBodyBytes(),
                properties.getOnUnreplayableResponse() == IdempotencyProperties.UnreplayableResponsePolicy.BLOCK,
                properties.getOnServerError() == IdempotencyProperties.ServerErrorPolicy.REPLAY));
        filterRegistrationBean.setOrder(IdempotencyDefaults.RESPONSE_FILTER_ORDER);
        return filterRegistrationBean;
    }

    /**
     * Adds the interceptor to Spring MVC for every path ({@code "/**"}).
     *
     * @param interceptor the library's interceptor
     * @return a configurer whose {@code addInterceptors} registers it
     */
    @Bean
    public WebMvcConfigurer idempotencyWebMvcConfigurer(IdempotencyInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**");
            }
        };
    }

    /**
     * Checks every handler once all beans exist (the handler mapping has found the controllers by then) and stops the
     * application if an {@code @Idempotent} handler completes asynchronously.
     *
     * @param mappings the application's handler mappings
     * @return the check, run by Spring after all singletons are created
     * @see AsyncHandlerGuard
     */
    @Bean
    public SmartInitializingSingleton idempotencyAsyncHandlerCheck(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return () -> mappings.orderedStream()
                .forEach(mapping -> IdempotentHandlerValidator.validate(mapping.getHandlerMethods().values()));
    }
}
