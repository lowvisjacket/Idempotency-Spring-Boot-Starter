package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F15: the application's own interceptor (here an API-key check, which does not set the servlet principal) must run
 * before the library may serve a saved response. Otherwise a caller who was never authenticated receives the response
 * that was saved for someone who was.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, InterceptorOrderTests.Api.class, InterceptorOrderTests.App.class})
class InterceptorOrderTests {

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired Api api;

    @BeforeEach
    void reset() {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        api.runs.set(0);
    }

    @Test
    void F15_aCallerWhoFailsTheApplicationsOwnAuthenticationNeverGetsASavedResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        int authorized = mvc.perform(post("/secret").header("Idempotency-Key", key).header("X-Api-Key", "good"))
                .andReturn().getResponse().getStatus();

        var intruder = mvc.perform(post("/secret").header("Idempotency-Key", key)).andReturn().getResponse();

        assertEquals(200, authorized);
        assertEquals(401, intruder.getStatus(), "served: " + intruder.getContentAsString());
        assertNull(intruder.getHeader("Idempotency-Replayed"));
        assertEquals(1, api.runs.get());
    }

    @RestController
    static class Api {
        final AtomicInteger runs = new AtomicInteger();

        @Idempotent
        @PostMapping("/secret")
        String secret() {
            return "card-token-" + runs.incrementAndGet();
        }
    }

    @TestConfiguration
    static class App {
        @Bean
        WebMvcConfigurer apiKeyAuth() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    // an explicit order, as applications use to sequence their interceptors; the library's own
                    // interceptor used to sit at the default order 0 and so ran before this one
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                                throws Exception {
                            if (!"good".equals(request.getHeader("X-Api-Key"))) {
                                response.sendError(401);
                                return false;
                            }
                            return true;
                        }
                    }).order(10);
                }
            };
        }
    }
}
