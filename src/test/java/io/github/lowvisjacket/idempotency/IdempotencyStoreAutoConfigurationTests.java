package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyStoreAutoConfiguration;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.RedisIdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which store gets created. Redis itself is never started: the template is a mock, because only the wiring is tested.
 */
class IdempotencyStoreAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IdempotencyStoreAutoConfiguration.class));

    @Test
    void createsTheRedisStoreWhenATemplateExists() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        runner.withBean(StringRedisTemplate.class, () -> template).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context).hasSingleBean(RedisIdempotencyStore.class);
            assertThat(ReflectionTestUtils.getField(context.getBean(RedisIdempotencyStore.class), "redisTemplate"))
                    .isSameAs(template);
        });
    }

    @Test
    void createsNoStoreWithoutATemplate() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
        });
    }

    @Test
    void applicationStoreWins() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean("myStore", IdempotencyStore.class, () -> mine)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isSameAs(mine);
                    assertThat(context).doesNotHaveBean(RedisIdempotencyStore.class);
                });
    }

    @Test
    void startsFineWhenRedisIsNotOnTheClasspath() {
        // Redis is an optional dependency: an application without it must still start.
        runner.withClassLoader(new FilteredClassLoader(StringRedisTemplate.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
        });
    }

    @Test
    void anApplicationStoreStillWorksWhenRedisIsNotOnTheClasspath() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withClassLoader(new FilteredClassLoader(StringRedisTemplate.class))
                .withBean("myStore", IdempotencyStore.class, () -> mine)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotencyStore.class)).isSameAs(mine);
                });
    }
}
