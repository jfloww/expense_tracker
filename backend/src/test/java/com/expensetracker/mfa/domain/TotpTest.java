package com.expensetracker.mfa.domain;

import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpTest {
    // RFC 6238 Appendix B SHA-1 secret, Base32 encoded; expected values reduced to six digits.
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238VectorsIncludingBeyondThirtyTwoBitTime() {
        long[] timestamps = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int index = 0; index < timestamps.length; index++) {
            assertEquals(expected[index], Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(timestamps[index])));
        }
    }

    @Test
    void acceptsOnlyAdjacentStepsAndRejectsReplayAndMalformedCodes() {
        Instant now = Instant.ofEpochSecond(1234567890);
        long step = now.getEpochSecond() / 30;
        for (long offset : new long[]{-1, 0, 1}) {
            String code = Totp.codeAt(RFC_SECRET, now.plusSeconds(offset * 30));
            assertEquals(step + offset, Totp.match(RFC_SECRET, code, now, -1).orElseThrow());
            assertTrue(Totp.match(RFC_SECRET, code, now, step + offset).isEmpty());
        }
        assertTrue(Totp.match(RFC_SECRET, Totp.codeAt(RFC_SECRET, now.minusSeconds(60)), now, -1).isEmpty());
        assertTrue(Totp.match(RFC_SECRET, Totp.codeAt(RFC_SECRET, now.plusSeconds(60)), now, -1).isEmpty());
        for (String malformed : new String[]{"", " 005924", "005924 ", "00592", "0005924", "１２３４５６", "abc123"}) {
            assertTrue(Totp.match(RFC_SECRET, malformed, now, -1).isEmpty());
        }
        assertTrue(Totp.match(RFC_SECRET, null, now, -1).isEmpty());
    }

    @Test
    void createsIndependentTwentyByteUnpaddedBase32Secrets() {
        var secrets = new HashSet<String>();
        for (int index = 0; index < 50; index++) {
            String secret = Totp.newSecret();
            assertTrue(secret.matches("[A-Z2-7]{32}"));
            assertTrue(secrets.add(secret));
            assertFalse(Totp.codeAt(secret, Instant.ofEpochSecond(59)).isBlank());
        }
    }
}
