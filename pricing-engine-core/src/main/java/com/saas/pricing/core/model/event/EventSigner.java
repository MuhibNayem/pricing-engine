package com.saas.pricing.core.model.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies outbox payloads, and decides when the next delivery attempt is due.
 *
 * <p><strong>Why sign at all.</strong> A webhook receiver that trusts an unauthenticated POST will
 * happily act on anyone who guesses the URL. An HMAC over {@code timestamp.payload} lets the
 * receiver prove the request came from this engine <em>and</em> that it was not replayed, because
 * the timestamp is inside the signed material.
 *
 * <p>The verification step is not optional sugar: an unsigned event is rejected outright rather
 * than delivered and logged, because a consumer that has been trained to ignore the signature will
 * never start checking it later.
 */
public final class EventSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long MAX_BACKOFF = Duration.ofHours(1).toMillis();
    private static final long BASE_BACKOFF_MILLIS = 1_000L;

    private EventSigner() {
        // static utility
    }

    /**
     * Signs {@code timestamp.payload}.
     *
     * @param secret the shared signing secret; must not be blank
     */
    public static String sign(String secret, Instant timestamp, String payload) {
        Objects.requireNonNull(secret, "secret cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("A signing secret cannot be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(signedMaterial(timestamp, payload)
                .getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign outbox payload", e);
        }
    }

    /**
     * Verifies a signature.
     *
     * <p>Uses a constant-time comparison so a mismatch does not leak how much of the signature was
     * correct.
     *
     * @param tolerance how far the timestamp may drift before the signature is treated as a replay
     */
    public static boolean verify(String secret, Instant timestamp, String payload, String signature,
                                 Duration tolerance) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(secret, timestamp, payload);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /** The exact bytes covered by the signature. Exposed so a receiver can reproduce it. */
    public static String signedMaterial(Instant timestamp, String payload) {
        return timestamp.getEpochSecond() + "." + payload;
    }

    /**
     * When the next delivery attempt is due, given how many have already failed.
     *
     * <p>Exponential backoff with <em>full jitter</em>. Without the jitter, every event queued in
     * the same batch retries on the same schedule and collides again at the same instant, which is
     * how a recovering receiver gets knocked over a second time.
     */
    public static Instant nextAttemptAt(int attemptsMade, Instant now) {
        long exponential = BASE_BACKOFF_MILLIS * (1L << Math.min(attemptsMade, 20));
        long capped = Math.min(exponential, MAX_BACKOFF);
        long delay = capped == 0 ? 0 : ThreadLocalRandom.current().nextLong(capped + 1);
        return now.plusMillis(delay);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}