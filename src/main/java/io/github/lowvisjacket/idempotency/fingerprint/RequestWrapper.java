package io.github.lowvisjacket.idempotency.fingerprint;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.FingerprintException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.Getter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads the request body once, up front, and lets it be read again any number of times.
 * Create it once per request (in a filter) and pass it down the chain, so the fingerprint
 * and the controller each get their own fresh stream over the same bytes.
 */
public class RequestWrapper extends HttpServletRequestWrapper {

    /**
     * REVIEW (later): 2048 bytes is small for real JSON bodies. When properties exist, make the limit a
     * configuration property that defaults to IdempotencyDefaults.MAX_BODY_SIZE_BYTES.
     */
    @Getter
    private final byte[] savedBody;

    /**
     * @param request The request to wrap
     * @throws IllegalArgumentException if the request is null
     */
    public RequestWrapper(HttpServletRequest request) throws IOException, FingerprintException {
        super(request);
        this.savedBody = request.getInputStream().readAllBytes();
        if (savedBody.length > IdempotencyDefaults.MAX_BODY_SIZE_BYTES) throw new FingerprintException("Body too large");
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(savedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return source.read(buffer, offset, length);
            }
        };
    }
}
