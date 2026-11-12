package io.github.lowvisjacket.idempotency.fingerprint;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * Computes a SHA-256 fingerprint of a request (method, path, query, body).
 * Stateless: the request is passed in, so one instance is safe to share across threads.
 */
public class Fingerprint {

    /**
     * @param request a request whose body was already saved by {@link RequestWrapper}
     */
    public String hash(RequestWrapper request) {
        MessageDigest digest = newDigest();

        addPart(digest, text(request.getMethod()));
        addPart(digest, text(request.getRequestURI()));
        addPart(digest, text(request.getQueryString() == null ? "" : request.getQueryString()));
        addPart(digest, normalizeBody(request.getSavedBody()));

        return HexFormat.of().formatHex(digest.digest());
    }

    private void addPart(MessageDigest digest, byte[] part) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
        digest.update(part);
    }

    private byte[] text(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Text bodies are Unicode-normalized so "é" typed as one character or as e + accent matches.
     * Anything that is not valid UTF-8 (binary uploads) is hashed exactly as sent; decoding it
     * would replace bad bytes and let different binary bodies collide.
     */
    private byte[] normalizeBody(byte[] body) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            return text(decoded);
        } catch (CharacterCodingException e) {
            return body;
        }
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but not available", e);
        }
    }
}
