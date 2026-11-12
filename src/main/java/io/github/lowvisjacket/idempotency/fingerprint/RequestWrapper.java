package io.github.lowvisjacket.idempotency.fingerprint;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.FingerprintException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Reads the start of the request body once, up front, so the fingerprint and the controller can both see it.
 *
 * <p><b>Memory is bounded.</b> At most {@link IdempotencyDefaults#MAX_BODY_SIZE_BYTES} + 1 bytes are ever read into
 * memory, whatever the client sends and whether or not the body has a declared length. A body that fits is kept in
 * full and can be read any number of times. A body that does not fit cannot be fingerprinted; the wrapper then
 * hands the controller the bytes already read followed by the rest of the original stream, so an endpoint that is
 * not {@code @Idempotent} (a large or chunked upload, say) still receives the whole body, unchanged and unbuffered.
 *
 * @see #capture(HttpServletRequest)
 * @see #withoutBody(HttpServletRequest)
 */
public class RequestWrapper extends HttpServletRequestWrapper {

    private final byte[] savedBody;
    /** True when the body is longer than the cap, so {@link #savedBody} is only its first bytes. */
    private final boolean exceedsCap;
    /**
     * A canonical form of the request's form fields, or empty. Form fields are read by the container (or by Spring's
     * form filter) <em>instead of</em> from the body stream, so they must be fingerprinted from the parameter map.
     */
    private final String formParameters;
    /** Only for {@link #exceedsCap}: the unread remainder of the container's stream. */
    private final ServletInputStream remainder;
    /** Only for {@link #exceedsCap}: the one stream handed out (the remainder can only be read once). */
    private ServletInputStream overflowStream;

    /**
     * Reads the whole body, which must fit in the cap.
     *
     * @param request The request to wrap
     * @throws FingerprintException if the body is longer than {@link IdempotencyDefaults#MAX_BODY_SIZE_BYTES}. At
     *                              most one byte more than the cap has been read by then.
     */
    public RequestWrapper(HttpServletRequest request) throws IOException, FingerprintException {
        super(request);
        this.savedBody = request.getInputStream().readNBytes(IdempotencyDefaults.MAX_BODY_SIZE_BYTES + 1);
        if (savedBody.length > IdempotencyDefaults.MAX_BODY_SIZE_BYTES) throw new FingerprintException("Body too large");
        this.exceedsCap = false;
        this.remainder = null;
        this.formParameters = "";
    }

    private RequestWrapper(HttpServletRequest request, byte[] savedBody, boolean exceedsCap, ServletInputStream remainder,
                           String formParameters) {
        super(request);
        this.savedBody = savedBody;
        this.exceedsCap = exceedsCap;
        this.remainder = remainder;
        this.formParameters = formParameters;
    }

    /**
     * Reads up to the cap plus one byte and never fails because the body is too long.
     *
     * <p>If the body is longer than the cap the result reports {@link #exceedsCap()} and still serves the complete
     * body to whoever reads it next; the caller should flag the request so the interceptor can refuse it on an
     * {@code @Idempotent} endpoint.
     */
    public static RequestWrapper capture(HttpServletRequest request) throws IOException {
        // A form body is turned into parameters by the container (POST) or by Spring's FormContentFilter (PUT, PATCH),
        // which consume the stream. Ask for the parameters first, so they are parsed from the intact body and stay
        // available to @RequestParam, and fingerprint them from the parameter map.
        String form = isForm(request) ? canonicalForm(request.getParameterMap()) : "";
        ServletInputStream in = request.getInputStream();
        byte[] head = in.readNBytes(IdempotencyDefaults.MAX_BODY_SIZE_BYTES + 1);
        boolean exceeds = head.length > IdempotencyDefaults.MAX_BODY_SIZE_BYTES
                || form.length() > IdempotencyDefaults.MAX_BODY_SIZE_BYTES;
        return new RequestWrapper(request, head, exceeds, exceeds ? in : null, form);
    }

    private static boolean isForm(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.regionMatches(true, 0, "application/x-www-form-urlencoded", 0, 33);
    }

    private static String canonicalForm(java.util.Map<String, String[]> parameters) {
        StringBuilder out = new StringBuilder();
        new java.util.TreeMap<>(parameters).forEach((name, values) -> {
            for (String value : values) {
                if (out.length() > 0) out.append('&');
                out.append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                        .append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return out.toString();
    }

    /**
     * A wrapper over an empty body that does not touch the request's stream. For methods the filter does not
     * buffer: the controller will read the real stream itself.
     */
    public static RequestWrapper withoutBody(HttpServletRequest request) {
        return new RequestWrapper(request, new byte[0], false, null, "");
    }

    /** @return the bytes read from the body: all of it, or only its first bytes when {@link #exceedsCap()} */
    public byte[] getSavedBody() {
        return savedBody;
    }

    /** @return the request's form fields in a canonical text form, or an empty string if it is not a form post */
    public String getFormParameters() {
        return formParameters;
    }

    /** @return true if the body is longer than the cap and so cannot be fingerprinted */
    public boolean exceedsCap() {
        return exceedsCap;
    }

    @Override
    public BufferedReader getReader() throws UnsupportedEncodingException {
        String encoding = getCharacterEncoding();
        Charset charset;
        try {
            charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException(encoding);
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public ServletInputStream getInputStream() {
        if (exceedsCap) {
            if (overflowStream == null) {
                overflowStream = new OverflowStream(savedBody, remainder);
            }
            return overflowStream;
        }
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

    /** The bytes already read, then the rest of the container's stream. */
    private static final class OverflowStream extends ServletInputStream {
        private final ByteArrayInputStream head;
        private final ServletInputStream rest;

        OverflowStream(byte[] head, ServletInputStream rest) {
            this.head = new ByteArrayInputStream(head);
            this.rest = rest;
        }

        @Override
        public int read() throws IOException {
            int b = head.read();
            return b != -1 ? b : rest.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (head.available() > 0) return head.read(buffer, offset, length);
            return rest.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return head.available() == 0 && rest.isFinished();
        }

        @Override
        public boolean isReady() {
            return head.available() > 0 || rest.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            rest.setReadListener(listener);
        }
    }
}
