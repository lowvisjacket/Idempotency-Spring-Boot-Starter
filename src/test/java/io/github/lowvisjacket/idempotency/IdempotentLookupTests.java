package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.web.AsyncHandlerGuard;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * F05: the developer reasonably believes these endpoints are protected. AsyncHandlerGuard.validate is used as the
 * probe because it only complains about methods the interceptor would treat as {@code @Idempotent}: a Callable
 * handler that is not recognised as idempotent is silently accepted.
 */
class IdempotentLookupTests {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Idempotent(ttl = "5m")
    @interface SafeToRetry {
    }

    @Idempotent
    static class AnnotatedBase {
    }

    @RestController
    static class InheritsFromBase extends AnnotatedBase {
        @PostMapping("/a")
        Callable<String> a() {
            return () -> "x";
        }
    }

    @Idempotent
    interface AnnotatedContract {
    }

    @RestController
    static class ImplementsContract implements AnnotatedContract {
        @PostMapping("/b")
        Callable<String> b() {
            return () -> "x";
        }
    }

    @SafeToRetry
    @RestController
    static class ComposedOnClass {
        @PostMapping("/c")
        Callable<String> c() {
            return () -> "x";
        }
    }

    @RestController
    static class ComposedOnMethod {
        @SafeToRetry
        @PostMapping("/d")
        Callable<String> d() {
            return () -> "x";
        }
    }

    interface Api {
        @Idempotent
        @PostMapping("/e")
        Callable<String> e();
    }

    @RestController
    static class ImplementsApi implements Api {
        @Override
        public Callable<String> e() {
            return () -> "x";
        }
    }

    private static void assertTreatedAsIdempotent(Class<?> controller, String method) throws Exception {
        HandlerMethod handler = new HandlerMethod(controller.getDeclaredConstructor().newInstance(), controller.getDeclaredMethod(method));
        assertThrows(IllegalStateException.class, () -> AsyncHandlerGuard.validate(List.of(handler)),
                controller.getSimpleName() + " was not treated as @Idempotent");
    }

    @Test
    void F05_annotationOnASuperclassIsHonoured() throws Exception {
        assertTreatedAsIdempotent(InheritsFromBase.class, "a");
    }

    @Test
    void F05_annotationOnAnInterfaceIsHonoured() throws Exception {
        assertTreatedAsIdempotent(ImplementsContract.class, "b");
    }

    @Test
    void F05_aComposedAnnotationOnAClassIsHonoured() throws Exception {
        assertTreatedAsIdempotent(ComposedOnClass.class, "c");
    }

    @Test
    void F05_aComposedAnnotationOnAMethodIsHonoured() throws Exception {
        assertTreatedAsIdempotent(ComposedOnMethod.class, "d");
    }

    @Test
    void F05_annotationOnAnInterfaceMethodIsHonoured() throws Exception {
        assertTreatedAsIdempotent(ImplementsApi.class, "e");
    }
}
