package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.web.CapturingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CapturingResponse}, written before the logic.
 *
 * <p>Rules these tests pin down:
 * <ul>
 *   <li>Before {@code startCapturing()} the wrapper is invisible: bytes reach the real response at once.</li>
 *   <li>While capturing, the body is held back (the real response gets nothing, and is not even committed) until
 *       {@code release()}, which sends it exactly once.</li>
 *   <li>Over the limit, everything held is sent in order and the rest passes straight through: the client always
 *       gets every byte, only the saving is given up. The limit is inclusive.</li>
 *   <li>Text written with the writer is captured too, with the declared charset, even if never flushed, and a
 *       surrogate pair split across two writes is not corrupted.</li>
 *   <li>Starting too late (output already obtained or committed) never yields a partial body: it overflows.</li>
 *   <li>Status and headers always go straight to the real response.</li>
 * </ul>
 */
class CapturingResponseTests {

    private final MockHttpServletResponse real = new MockHttpServletResponse();

    private CapturingResponse passThrough(int max) {
        return new CapturingResponse(real, max);
    }

    private CapturingResponse capturing(int max) {
        CapturingResponse response = passThrough(max);
        response.startCapturing();
        return response;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sent() {
        return real.getContentAsByteArray();
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }

    @Test
    void aNullResponseIsRejected() {
        assertThrows(NullPointerException.class, () -> new CapturingResponse(null, 10));
    }

    @ParameterizedTest(name = "limit {0} is rejected")
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE})
    void aNegativeLimitIsRejected(int max) {
        assertThrows(IllegalArgumentException.class, () -> new CapturingResponse(real, max));
    }

    @Test
    void aLimitOfZeroIsAllowed() {
        assertDoesNotThrow(() -> new CapturingResponse(real, 0));
    }

    @Test
    void beforeCapturingStartsBytesReachTheRealResponseAtOnce() throws Exception {
        CapturingResponse response = passThrough(16);

        response.getOutputStream().write(utf8("hello"));

        assertArrayEquals(utf8("hello"), sent());
        assertFalse(response.isCapturing());
        assertFalse(response.isOverflowed());
    }

    @Test
    void beforeCapturingStartsWriterTextReachesTheRealResponse() throws Exception {
        CapturingResponse response = passThrough(16);
        response.setCharacterEncoding("UTF-8");

        PrintWriter writer = response.getWriter();
        writer.write("héllo");
        writer.flush();

        assertArrayEquals(utf8("héllo"), sent());
    }

    @Test
    void aBodyLongerThanTheLimitIsNotAProblemWhileNotCapturing() throws Exception {
        CapturingResponse response = passThrough(4);

        response.getOutputStream().write(pattern(1000));

        assertArrayEquals(pattern(1000), sent());
        assertFalse(response.isOverflowed(), "overflow only means something once capturing has started");
    }

    @Test
    void theCapturedBodyIsNotAvailableBeforeCapturingStarts() {
        assertThrows(IllegalStateException.class, () -> passThrough(16).getCapturedBody());
    }

    @Test
    void releasingWhenNeverCapturingChangesNothing() throws Exception {
        CapturingResponse response = passThrough(16);
        response.getOutputStream().write(utf8("abc"));

        response.release();

        assertArrayEquals(utf8("abc"), sent());
    }

    @Test
    void whileCapturingTheBodyIsHeldBackFromTheRealResponse() throws Exception {
        CapturingResponse response = capturing(16);

        response.getOutputStream().write(utf8("hello"));

        assertTrue(response.isCapturing());
        assertEquals(0, sent().length);
        assertArrayEquals(utf8("hello"), response.getCapturedBody());
    }

    @Test
    void releaseSendsTheHeldBodyToTheRealResponse() throws Exception {
        CapturingResponse response = capturing(16);
        response.getOutputStream().write(utf8("hello"));

        response.release();

        assertArrayEquals(utf8("hello"), sent());
    }

    @Test
    void releaseNeverSendsTheSameBytesTwice() throws Exception {
        CapturingResponse response = capturing(16);
        response.getOutputStream().write(utf8("hello"));

        response.release();
        response.release();
        response.release();

        assertArrayEquals(utf8("hello"), sent());
    }

    @Test
    void releaseMovesBackToPassThroughSoLaterBytesGoStraightOut() throws Exception {
        CapturingResponse response = capturing(16);
        OutputStream out = response.getOutputStream();
        out.write(utf8("head"));

        response.release();
        out.write(utf8("tail"));

        assertArrayEquals(utf8("headtail"), sent());
        assertFalse(response.isCapturing());
        assertThrows(IllegalStateException.class, response::getCapturedBody);
    }

    @Test
    void severalWritesAreHeldInOrder() throws Exception {
        CapturingResponse response = capturing(32);
        OutputStream out = response.getOutputStream();

        out.write(utf8("one-"));
        out.write(utf8("two-"));
        out.write(utf8("three"));

        assertArrayEquals(utf8("one-two-three"), response.getCapturedBody());
    }

    @Test
    void aSingleByteWriteKeepsAllEightBits() throws Exception {
        CapturingResponse response = capturing(16);

        response.getOutputStream().write(0xFF);
        response.getOutputStream().write(0x80);
        response.getOutputStream().write(0x00);

        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0x80, 0x00}, response.getCapturedBody());
    }

    @Test
    void aWriteWithAnOffsetAndLengthTakesOnlyThatSlice() throws Exception {
        CapturingResponse response = capturing(16);

        response.getOutputStream().write(utf8("0123456789"), 2, 3);

        assertArrayEquals(utf8("234"), response.getCapturedBody());
    }

    @Test
    void aBodyThatIsNeverWrittenIsEmptyNotNull() throws Exception {
        CapturingResponse response = capturing(16);

        assertEquals(0, response.getCapturedBody().length);
        response.release();
        assertEquals(0, sent().length);
    }

    @Test
    void theCapturedBodyIsACopy() throws Exception {
        CapturingResponse response = capturing(16);
        response.getOutputStream().write(utf8("hello"));

        byte[] copy = response.getCapturedBody();
        Arrays.fill(copy, (byte) 'X');

        assertArrayEquals(utf8("hello"), response.getCapturedBody());
        response.release();
        assertArrayEquals(utf8("hello"), sent());
    }

    @Test
    void startingTwiceKeepsWhatIsAlreadyHeld() throws Exception {
        CapturingResponse response = capturing(16);
        response.getOutputStream().write(utf8("ab"));

        response.startCapturing();

        assertTrue(response.isCapturing());
        assertArrayEquals(utf8("ab"), response.getCapturedBody());
    }

    @Test
    void flushingWhileCapturingSendsNothingAndDoesNotCommit() throws Exception {
        CapturingResponse response = capturing(16);
        response.getOutputStream().write(utf8("hello"));

        response.flushBuffer();
        response.getOutputStream().flush();

        assertFalse(real.isCommitted(), "a committed response could no longer be replaced or reset");
        assertEquals(0, sent().length);
        assertArrayEquals(utf8("hello"), response.getCapturedBody());
    }

    @ParameterizedTest(name = "exactly {0} bytes still fits")
    @ValueSource(ints = {1, 10, 16})
    void aBodyExactlyAtTheLimitIsStillCaptured(int max) throws Exception {
        CapturingResponse response = capturing(max);

        response.getOutputStream().write(pattern(max));

        assertTrue(response.isCapturing());
        assertFalse(response.isOverflowed());
        assertArrayEquals(pattern(max), response.getCapturedBody());
        assertEquals(0, sent().length);
    }

    @ParameterizedTest(name = "limit {0} plus one byte overflows")
    @ValueSource(ints = {0, 1, 10, 16})
    void oneByteOverTheLimitOverflowsAndTheClientGetsEverything(int max) throws Exception {
        CapturingResponse response = capturing(max);

        response.getOutputStream().write(pattern(max + 1));

        assertTrue(response.isOverflowed());
        assertFalse(response.isCapturing());
        assertArrayEquals(pattern(max + 1), sent(), "nothing may be lost or reordered");
        assertThrows(IllegalStateException.class, response::getCapturedBody);
    }

    @Test
    void anOverflowSendsEarlierWritesFirstAndInOrder() throws Exception {
        CapturingResponse response = capturing(16);
        OutputStream out = response.getOutputStream();

        out.write(utf8("aaaaaa"));
        out.write(utf8("bbbbbb"));
        assertEquals(0, sent().length, "still within the limit, still held");
        out.write(utf8("cccccc"));

        assertTrue(response.isOverflowed());
        assertArrayEquals(utf8("aaaaaabbbbbbcccccc"), sent());
    }

    @Test
    void afterAnOverflowLaterBytesPassStraightThrough() throws Exception {
        CapturingResponse response = capturing(4);
        OutputStream out = response.getOutputStream();
        out.write(utf8("overflow!"));

        out.write(utf8(" and more"));
        out.write('!');

        assertArrayEquals(utf8("overflow! and more!"), sent());
    }

    @Test
    void aSingleHugeWriteOverflowsAtOnceWithoutLosingBytes() throws Exception {
        CapturingResponse response = capturing(16);
        byte[] huge = pattern(1024 * 1024);

        response.getOutputStream().write(huge);

        assertTrue(response.isOverflowed());
        assertArrayEquals(huge, sent());
    }

    @Test
    void releaseAfterAnOverflowDoesNotSendAnythingAgain() throws Exception {
        CapturingResponse response = capturing(4);
        response.getOutputStream().write(utf8("overflow!"));

        response.release();
        response.release();

        assertArrayEquals(utf8("overflow!"), sent());
    }

    @Test
    void aLimitOfZeroCapturesAnEmptyBodyAndOverflowsOnTheFirstByte() throws Exception {
        CapturingResponse empty = capturing(0);
        assertTrue(empty.isCapturing());
        assertEquals(0, empty.getCapturedBody().length);

        CapturingResponse oneByte = new CapturingResponse(new MockHttpServletResponse(), 0);
        oneByte.startCapturing();
        oneByte.getOutputStream().write('x');
        assertTrue(oneByte.isOverflowed());
    }

    @Test
    void theWriterEncodesWithTheDeclaredCharset() throws Exception {
        CapturingResponse response = passThrough(64);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();
        String text = "José ✓ 日本語 😀";

        PrintWriter writer = response.getWriter();
        writer.write(text);
        writer.flush();

        assertArrayEquals(utf8(text), response.getCapturedBody());
    }

    @Test
    void theWriterFollowsAnotherDeclaredCharsetToo() throws Exception {
        CapturingResponse response = passThrough(64);
        response.setCharacterEncoding("ISO-8859-1");
        response.startCapturing();

        response.getWriter().write("é");

        assertArrayEquals(new byte[]{(byte) 0xE9}, response.getCapturedBody());
    }

    @Test
    void textThatWasNeverFlushedIsStillCapturedAndSent() throws Exception {
        CapturingResponse response = passThrough(64);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();

        response.getWriter().write("never flushed");

        assertArrayEquals(utf8("never flushed"), response.getCapturedBody());
        response.release();
        assertArrayEquals(utf8("never flushed"), sent(), "release flushes the writer before sending");
    }

    @Test
    void writerTextIsHeldBackEvenAfterTheWriterIsFlushed() throws Exception {
        CapturingResponse response = passThrough(64);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();

        response.getWriter().write("held");
        response.getWriter().flush();

        assertEquals(0, sent().length);
        assertFalse(real.isCommitted());
    }

    @Test
    void aSurrogatePairSplitAcrossTwoWritesIsNotCorrupted() throws Exception {
        CapturingResponse response = passThrough(64);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();

        Writer writer = response.getWriter();
        writer.write("\uD83D");
        writer.write("\uDE00");
        writer.flush();

        assertArrayEquals(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}, response.getCapturedBody());
    }

    @Test
    void writerTextOverTheLimitOverflowsAndIsSentInOrder() throws Exception {
        CapturingResponse response = passThrough(4);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();

        PrintWriter writer = response.getWriter();
        writer.write("abcdefgh");
        writer.flush();

        assertTrue(response.isOverflowed());
        response.release();
        assertArrayEquals(utf8("abcdefgh"), sent());
    }

    @Test
    void overflowCausedByUnflushedWriterTextIsStillReported() throws Exception {
        CapturingResponse response = passThrough(4);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();

        response.getWriter().write("abcdefgh");

        assertTrue(response.isOverflowed(), "isOverflowed must flush the writer before answering");
    }

    @ParameterizedTest(name = "capturing started: {0}")
    @ValueSource(booleans = {false, true})
    void theWriterIsRefusedAfterTheStream(boolean started) throws Exception {
        CapturingResponse response = started ? capturing(16) : passThrough(16);
        response.getOutputStream();

        assertThrows(IllegalStateException.class, response::getWriter);
    }

    @ParameterizedTest(name = "capturing started: {0}")
    @ValueSource(booleans = {false, true})
    void theStreamIsRefusedAfterTheWriter(boolean started) throws Exception {
        CapturingResponse response = started ? capturing(16) : passThrough(16);
        response.getWriter();

        assertThrows(IllegalStateException.class, response::getOutputStream);
    }

    @ParameterizedTest(name = "capturing started: {0}")
    @ValueSource(booleans = {false, true})
    void askingTwiceGivesTheSameStreamAndTheSameWriter(boolean started) throws Exception {
        CapturingResponse streams = started ? capturing(16) : passThrough(16);
        assertSame(streams.getOutputStream(), streams.getOutputStream());

        CapturingResponse writers = started
                ? capturing(16)
                : new CapturingResponse(new MockHttpServletResponse(), 16);
        assertSame(writers.getWriter(), writers.getWriter());
    }

    @Test
    void resetBufferDiscardsTheHeldBody() throws Exception {
        CapturingResponse response = capturing(32);
        OutputStream out = response.getOutputStream();
        out.write(utf8("old"));

        response.resetBuffer();
        out.write(utf8("new"));

        assertArrayEquals(utf8("new"), response.getCapturedBody());
        response.release();
        assertArrayEquals(utf8("new"), sent());
    }

    @Test
    void resetBufferAlsoDiscardsWriterTextThatWasNotFlushedYet() throws Exception {
        CapturingResponse response = passThrough(32);
        response.setCharacterEncoding("UTF-8");
        response.startCapturing();
        PrintWriter writer = response.getWriter();
        writer.write("old");

        response.resetBuffer();
        writer.write("new");
        writer.flush();

        assertArrayEquals(utf8("new"), response.getCapturedBody());
    }

    @Test
    void resetAlsoClearsTheStatusAndHeaders() throws Exception {
        CapturingResponse response = capturing(32);
        response.setStatus(500);
        response.setHeader("X-Attempt", "1");
        response.getOutputStream().write(utf8("error page"));

        response.reset();

        assertEquals(200, response.getStatus());
        assertNull(real.getHeader("X-Attempt"));
        assertEquals(0, response.getCapturedBody().length);
    }

    @Test
    void aBodyWrittenAfterAResetFitsTheLimitAgain() throws Exception {
        CapturingResponse response = capturing(8);
        OutputStream out = response.getOutputStream();
        out.write(utf8("12345678"));

        response.resetBuffer();
        out.write(utf8("abcdefgh"));

        assertTrue(response.isCapturing(), "the limit counts what is held now, not everything ever written");
        assertArrayEquals(utf8("abcdefgh"), response.getCapturedBody());
    }

    @Test
    void startingAfterTheStreamWasObtainedOverflowsAndTouchesNothing() throws Exception {
        CapturingResponse response = passThrough(16);
        OutputStream out = response.getOutputStream();
        out.write(utf8("early"));

        response.startCapturing();
        out.write(utf8(" late"));

        assertFalse(response.isCapturing());
        assertTrue(response.isOverflowed());
        assertArrayEquals(utf8("early late"), sent());
        assertThrows(IllegalStateException.class, response::getCapturedBody);
    }

    @Test
    void startingAfterTheWriterWasObtainedOverflowsToo() throws Exception {
        CapturingResponse response = passThrough(16);
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();

        response.startCapturing();
        writer.write("text");
        writer.flush();

        assertTrue(response.isOverflowed());
        assertArrayEquals(utf8("text"), sent());
    }

    @Test
    void startingOnAnAlreadyCommittedResponseOverflows() throws Exception {
        real.getOutputStream().write(utf8("already out"));
        real.flushBuffer();
        CapturingResponse response = passThrough(16);

        response.startCapturing();

        assertTrue(response.isOverflowed());
        assertFalse(response.isCapturing());
    }

    @ParameterizedTest(name = "capturing started: {0}")
    @ValueSource(booleans = {false, true})
    void theStatusAndHeadersAreNeverHeldBack(boolean started) {
        CapturingResponse response = started ? capturing(16) : passThrough(16);

        response.setStatus(201);
        response.setHeader("X-One", "1");
        response.addHeader("X-Many", "a");
        response.addHeader("X-Many", "b");
        response.setContentType("application/json");

        assertEquals(201, real.getStatus());
        assertEquals(201, response.getStatus());
        assertEquals("1", real.getHeader("X-One"));
        assertEquals(java.util.List.of("a", "b"), real.getHeaders("X-Many"));
        assertEquals("application/json", real.getContentType());
    }

    @Test
    void sendErrorSetsTheStatusTheFilterWillSee() throws Exception {
        CapturingResponse response = capturing(16);

        response.sendError(404);

        assertEquals(404, response.getStatus());
        assertEquals(404, real.getStatus());
    }
}
