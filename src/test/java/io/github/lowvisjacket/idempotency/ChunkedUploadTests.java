package io.github.lowvisjacket.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import io.github.lowvisjacket.idempotency.annotation.Idempotent;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F01/F02: with a real servlet container (embedded Tomcat) a chunked request has no Content-Length, which is the
 * case MockMvc cannot reproduce. Merely adding the starter must not break or exhaust memory on an unrelated upload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ChunkedUploadTests.Api.class})
class ChunkedUploadTests {

    @Value("${local.server.port}") int port;

    private static InputStream zeros(long total) {
        return new InputStream() {
            long sent = 0;

            @Override
            public int read() {
                return sent++ < total ? 0 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (sent >= total) return -1;
                int n = (int) Math.min(len, total - sent);
                sent += n;
                return n;
            }
        };
    }

    private HttpResponse<String> post(String path, long bytes, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> zeros(bytes)));
        if (key != null) request.header("Idempotency-Key", key);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void F02_aChunkedUploadToAnUnrelatedEndpointStillArrivesInFull() throws Exception {
        long size = 5L * 1024 * 1024;
        HttpResponse<String> response = post("/upload", size, null);

        assertEquals(200, response.statusCode());
        assertEquals(Long.toString(size), response.body());
    }

    @Test
    void F01_aChunkedBodyOnAnIdempotentEndpointIsRefusedWith413() throws Exception {
        HttpResponse<String> response = post("/idempotent-upload", 5L * 1024 * 1024, UUID.randomUUID().toString());

        assertEquals(413, response.statusCode());
    }

    @RestController
    static class Api {
        @PostMapping("/upload")
        String upload(HttpServletRequest request) throws Exception {
            long n = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = request.getInputStream()) {
                int r;
                while ((r = in.read(buf)) != -1) n += r;
            }
            return Long.toString(n);
        }

        @Idempotent
        @PostMapping("/idempotent-upload")
        String idempotentUpload() {
            return "ran";
        }
    }
}
