package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyMissingStoreAutoConfiguration;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyStoreAutoConfiguration;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** F06 and F07: mistakes that used to leave an endpoint silently unprotected or failing on every request. */
class StartupValidationTests {

    @RestController
    static class Controller {
        @Idempotent
        @PostMapping("/orders")
        String order() {
            return "ok";
        }
    }

    @RestController
    static class BadTtl {
        @Idempotent(ttl = "tomorrow")
        @PostMapping("/bad-ttl")
        String order() {
            return "ok";
        }
    }

    @RestController
    static class BlankHeader {
        @Idempotent(header = "")
        @PostMapping("/blank-header")
        String order() {
            return "ok";
        }
    }

    private final WebApplicationContextRunner noStore = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    IdempotencyStoreAutoConfiguration.class, IdempotencyAutoConfiguration.class,
                    IdempotencyMissingStoreAutoConfiguration.class))
            .withUserConfiguration(EnableWebMvcConfig.class);

    @EnableWebMvc
    static class EnableWebMvcConfig {
    }

    private final WebApplicationContextRunner withStore = noStore.withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class));

    @Test
    void F06_idempotentEndpointsWithNoStoreStopTheApplicationAtStartup() {
        noStore.withUserConfiguration(Controller.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("no IdempotencyStore")
                    .hasMessageContaining("Controller#order");
        });
    }

    @Test
    void F06_noIdempotentEndpointsAndNoStoreStartsNormally() {
        noStore.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void F06_aDisabledLibraryDoesNotComplainAboutMissingStore() {
        noStore.withUserConfiguration(Controller.class).withPropertyValues("idempotency.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void F07_anInvalidTtlStopsTheApplicationAtStartup() {
        withStore.withUserConfiguration(BadTtl.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("BadTtl#order");
        });
    }

    @Test
    void F07_aBlankHeaderNameStopsTheApplicationAtStartup() {
        withStore.withUserConfiguration(BlankHeader.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("BlankHeader#order");
        });
    }

    @Test
    void F07_aValidEndpointStarts() {
        withStore.withUserConfiguration(Controller.class).run(context -> assertThat(context).hasNotFailed());
    }
}
