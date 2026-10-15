package com.tracom.lipamdogo.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Offline unlock codes for pay-as-you-go devices.
 *
 * <p>A code is 12 digits: {@code SSS DDD MMMMMM}.</p>
 * <ul>
 *   <li>{@code SSS} — sequence number (001–999). The device only accepts a
 *       code whose sequence is higher than the last one it accepted, so old
 *       codes cannot be replayed.</li>
 *   <li>{@code DDD} — "unlocked until" as days after the contract's anchor
 *       date (000–998), or {@code 999} for a permanent unlock (paid off).</li>
 *   <li>{@code MMMMMM} — a 6-digit message authentication code: HMAC-SHA256
 *       over the payload with the device's secret key, reduced to six digits
 *       with the dynamic truncation from RFC 4226 (HOTP).</li>
 * </ul>
 *
 * <p>The payload is readable but cannot be changed: altering the sequence or
 * the date breaks the MAC, and only the server and the device hold the key.
 * The device verifies codes with no network connection. {@code js/unlock-codec.js}
 * implements the same scheme for the phone simulator; both are checked against
 * the same known-answer vector in the tests.</p>
 */
public final class UnlockCodeCodec {

    public static final int PERMANENT = 999;
    public static final int MAX_DAYS = 998;
    public static final int MAX_SEQUENCE = 999;
    private static final String DOMAIN = "MSIMU-UNLOCK-V1";
    private static final HexFormat HEX = HexFormat.of();

    private UnlockCodeCodec() {
    }

    public record Decoded(int sequence, int days) {
        public boolean permanent() {
            return days == PERMANENT;
        }
    }

    /** Issues a code. {@code days} must be 0–998, or {@link #PERMANENT}. */
    public static String encode(String secretHex, String deviceCode, int sequence, int days) {
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Unlock sequence must be between 1 and " + MAX_SEQUENCE + ".");
        }
        if (days != PERMANENT && (days < 0 || days > MAX_DAYS)) {
            throw new IllegalArgumentException("Unlock days must be between 0 and " + MAX_DAYS + ".");
        }
        String payload = "%03d%03d".formatted(sequence, days);
        return payload + "%06d".formatted(mac(secretHex, deviceCode, payload));
    }

    /**
     * Verifies a code typed by a person (spaces and dashes ignored). Returns
     * the decoded payload only when the MAC is valid for this device's key.
     */
    public static Optional<Decoded> verify(String secretHex, String deviceCode, String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String digits = typed.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{12}")) {
            return Optional.empty();
        }
        String payload = digits.substring(0, 6);
        String expected = "%06d".formatted(mac(secretHex, deviceCode, payload));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                digits.substring(6).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        int sequence = Integer.parseInt(payload.substring(0, 3));
        int days = Integer.parseInt(payload.substring(3, 6));
        if (sequence < 1) {
            return Optional.empty();
        }
        return Optional.of(new Decoded(sequence, days));
    }

    /** "123456789012" → "1234 5678 9012" for display and SMS. */
    public static String format(String code) {
        return code.substring(0, 4) + " " + code.substring(4, 8) + " " + code.substring(8, 12);
    }

    static int mac(String secretHex, String deviceCode, String payload) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(HEX.parseHex(secretHex), "HmacSHA256"));
            byte[] h = hmac.doFinal((DOMAIN + "|" + deviceCode + "|" + payload).getBytes(StandardCharsets.UTF_8));
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24)
                    | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
