package io.github.lowvisjacket.idempotency;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * The application the Spring Boot tests start.
 *
 * <p>Deliberately NOT {@code @SpringBootApplication}: that also component-scans this package, and the library's own
 * classes live in sub-packages of it. Scanning would register the library's auto-configuration as if it were the
 * application's own configuration, in the wrong order, which hides exactly the bugs the tests are meant to find.
 * With only {@code @EnableAutoConfiguration} the library is wired the way a real application gets it.
 * Tests that need extra beans add them with {@code @Import}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class EntryClass {

}
