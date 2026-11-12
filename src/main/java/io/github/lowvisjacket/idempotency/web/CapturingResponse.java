package io.github.lowvisjacket.idempotency.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * A response wrapper that can hold the body back, so the library can save it before the client sees it.
 *
 * <p>It has three states:
 * <ul>
 *   <li><b>Pass-through</b> (the start): everything goes straight to the real response, as if the wrapper were
 *       not there. Most requests never leave this state, so they cost nothing extra.</li>
 *   <li><b>Capturing</b> (after {@link #startCapturing()}): body bytes are kept in memory, up to the limit, and are
 *       <em>not</em> sent. {@link #release()} sends them. Status and headers are never held: they go to the real
 *       response at once, whatever the state.</li>
 *   <li><b>Overflowed</b>: the body went over the limit (or capturing began too late). Everything held so far is
 *       sent to the real response, in order, and from then on bytes pass straight through. The body can no longer
 *       be saved, but the client always receives it in full and unchanged.</li>
 * </ul>
 *
 * <p><b>Why hold the body back at all?</b> The record must be saved <em>before</em> the client gets the response.
 * If the response went out first, a client that retries immediately could find the record still {@code PENDING}
 * and get a 409.
 *
 * <p><b>Writer or stream.</b> Like any servlet response, either {@link #getWriter()} or {@link #getOutputStream()}
 * may be used, not both: the second call throws {@link IllegalStateException}. Text written with the writer is
 * encoded with {@link #getCharacterEncoding()} as it was when the writer was first requested. While capturing, text
 * the writer has not yet flushed still counts as body: {@link #getCapturedBody()}, {@link #isOverflowed()} and
 * {@link #release()} flush it first.
 *
 * <p>Not thread-safe: a response belongs to one request thread.
 *
 * @see ResponseCaptureFilter
 */
public class CapturingResponse extends HttpServletResponseWrapper {

    private enum Mode { PASS_THROUGH, CAPTURING, OVERFLOWED, RELEASED }

    private final int maxBytes;
    private Mode mode = Mode.PASS_THROUGH;
    /** The bucket: body bytes held back while capturing. */
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    /** Our own stream, created the first time the controller needs one while we hold bytes back. */
    private ServletOutputStream stream;
    /** Our own writer (sits on top of {@link #stream}); null when the container's writer is used. */
    private PrintWriter capturingWriter;
    /** The writer handed out (ours or the container's), so repeated calls return the same one. */
    private PrintWriter writer;
    private boolean outputObtained;
    private boolean writerObtained;

    /**
     * @param response the real response
     * @param maxBytes the most body bytes to hold while capturing; zero means only an empty body can be captured
     * @throws NullPointerException     if {@code response} is null
     * @throws IllegalArgumentException if {@code maxBytes} is negative
     */
    public CapturingResponse(HttpServletResponse response, int maxBytes) {
        super(Objects.requireNonNull(response, "response"));
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        this.maxBytes = maxBytes;
    }

    /**
     * Begins holding the body back. Only has an effect from pass-through. If the controller already asked for the
     * stream or writer, or the response is committed, bytes may already be on their way, so we go straight to
     * overflowed (never half-capture).
     */
    public void startCapturing() {
        if (mode != Mode.PASS_THROUGH) {
            return;
        }
        mode = (outputObtained || writerObtained || isCommitted()) ? Mode.OVERFLOWED : Mode.CAPTURING;
    }

    /** @return true only while body bytes are being held (false before start, after overflow and after release) */
    public boolean isCapturing() {
        return mode == Mode.CAPTURING;
    }

    /** @return true if the body went over the limit or capturing began too late; unflushed writer text counts */
    public boolean isOverflowed() {
        flushWriter();
        return mode == Mode.OVERFLOWED;
    }

    /**
     * @return a copy of the held body (empty array, never null, for an empty body); unflushed writer text included
     * @throws IllegalStateException unless currently capturing
     */
    public byte[] getCapturedBody() {
        flushWriter();
        if (mode != Mode.CAPTURING) {
            throw new IllegalStateException("The body is only available while capturing (mode: " + mode + ")");
        }
        return buffer.toByteArray();
    }

    /**
     * Sends the held body to the real response. Safe to call more than once: only the first call after capturing
     * sends anything. Does nothing if not capturing.
     */
    public void release() throws IOException {
        flushWriter();
        if (mode == Mode.CAPTURING) {
            if (buffer.size() > 0) {
                buffer.writeTo(getResponse().getOutputStream());
                buffer.reset();
            }
            mode = Mode.RELEASED;
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writerObtained) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        outputObtained = true;
        if (mode == Mode.CAPTURING || stream != null) {
            if (stream == null) {
                stream = new HoldingStream();
            }
            return stream;
        }
        return super.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputObtained) {
            throw new IllegalStateException("getOutputStream() has already been called on this response");
        }
        if (writer != null) {
            return writer;
        }
        writerObtained = true;
        if (mode == Mode.CAPTURING) {
            stream = new HoldingStream();
            capturingWriter = new PrintWriter(new OutputStreamWriter(stream, getCharacterEncoding()));
            writer = capturingWriter;
        } else {
            writer = super.getWriter();
        }
        return writer;
    }

    /** While capturing, sends nothing (flushing would commit the response and send the body early). */
    @Override
    public void flushBuffer() throws IOException {
        flushWriter();
        if (mode != Mode.CAPTURING) {
            super.flushBuffer();
        }
    }

    @Override
    public void resetBuffer() {
        flushWriter();
        super.resetBuffer();
        if (mode == Mode.CAPTURING) {
            buffer.reset();
        }
    }

    @Override
    public void reset() {
        flushWriter();
        super.reset();
        if (mode == Mode.CAPTURING) {
            buffer.reset();
        }
    }

    /** Moves text still sitting in our writer's own buffer into the bucket. Never touches the container's writer. */
    private void flushWriter() {
        if (capturingWriter != null) {
            capturingWriter.flush();
        }
    }

    /** The stream the controller writes to while we hold bytes back. All the logic is in one write method. */
    private final class HoldingStream extends ServletOutputStream {

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (mode == Mode.CAPTURING) {
                if ((long) buffer.size() + len <= maxBytes) {
                    buffer.write(b, off, len);
                    return;
                }
                mode = Mode.OVERFLOWED;
                buffer.writeTo(getResponse().getOutputStream());
                buffer.reset();
            }
            getResponse().getOutputStream().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (mode != Mode.CAPTURING) {
                getResponse().getOutputStream().flush();
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            throw new UnsupportedOperationException("async IO is not supported here");
        }
    }
}
