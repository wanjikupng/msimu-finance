package com.tracom.lipamdogo;

import com.tracom.lipamdogo.security.UnlockCodeCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unlock codes: known-answer vectors (shared with js/unlock-codec.js and an
 * independent Python HMAC computation), round trips and tamper resistance.
 */
class UnlockCodeCodecTest {

    /** 32 bytes 0x00..0x1f. */
    private static final String KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void matchesKnownAnswerVectors() {
        assertThat(UnlockCodeCodec.encode(KEY, "TRC-000131", 7, 45)).isEqualTo("007045352103");
        assertThat(UnlockCodeCodec.encode(KEY, "TRC-000131", 12, UnlockCodeCodec.PERMANENT)).isEqualTo("012999742741");
        assertThat(UnlockCodeCodec.encode(KEY, "TRC-000999", 7, 45)).isEqualTo("007045178791");
    }

    @Test
    void verifiesItsOwnCodesIncludingFormattedInput() {
        var decoded = UnlockCodeCodec.verify(KEY, "TRC-000131", "0070 4535 2103").orElseThrow();
        assertThat(decoded.sequence()).isEqualTo(7);
        assertThat(decoded.days()).isEqualTo(45);
        assertThat(decoded.permanent()).isFalse();
        assertThat(UnlockCodeCodec.verify(KEY, "TRC-000131", "012-999-742-741").orElseThrow().permanent()).isTrue();
    }

    @Test
    void rejectsTamperedPayloadOtherDeviceAndOtherKey() {
        // Customer edits the date from 045 to 145 to buy 100 free days.
        assertThat(UnlockCodeCodec.verify(KEY, "TRC-000131", "007145352103")).isEmpty();
        // A valid code for one phone does not unlock another.
        assertThat(UnlockCodeCodec.verify(KEY, "TRC-000999", "007045352103")).isEmpty();
        String otherKey = "ff" + KEY.substring(2);
        assertThat(UnlockCodeCodec.verify(otherKey, "TRC-000131", "007045352103")).isEmpty();
        assertThat(UnlockCodeCodec.verify(KEY, "TRC-000131", "12345")).isEmpty();
        assertThat(UnlockCodeCodec.verify(KEY, "TRC-000131", null)).isEmpty();
    }

    @Test
    void refusesOutOfRangeFields() {
        assertThatThrownBy(() -> UnlockCodeCodec.encode(KEY, "TRC-1", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnlockCodeCodec.encode(KEY, "TRC-1", 1000, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnlockCodeCodec.encode(KEY, "TRC-1", 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsForDisplay() {
        assertThat(UnlockCodeCodec.format("007045352103")).isEqualTo("0070 4535 2103");
    }
}
