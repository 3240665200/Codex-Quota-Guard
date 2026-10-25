package com.pingche.web.authenticator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TotpTest {
    @ParameterizedTest
    @CsvSource({
        "59,94287082,46119246,90693936", "1111111109,07081804,68084774,25091201",
        "1111111111,14050471,67062674,99943326", "1234567890,89005924,91819424,93441116",
        "2000000000,69279037,90698825,38618901", "20000000000,65353130,77737706,47863826"
    })
    void matchesAllRfc6238Vectors(long seconds, String sha1, String sha256, String sha512) {
        Instant time = Instant.ofEpochSecond(seconds);
        assertThat(Totp.code(base32("12345678901234567890"), "SHA1", 8, 30, time)).isEqualTo(sha1);
        assertThat(Totp.code(base32("12345678901234567890123456789012"), "SHA256", 8, 30, time)).isEqualTo(sha256);
        assertThat(Totp.code(base32("1234567890123456789012345678901234567890123456789012345678901234"), "SHA512", 8, 30, time)).isEqualTo(sha512);
    }
    @Test void supportsGoogleDefaultsAndStandardProvisioningLinks() {
        var provision = Totp.parse("otpauth://totp/OpenAI:team?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=OpenAI");
        assertThat(provision.algorithm()).isEqualTo("SHA1");
        assertThat(provision.digits()).isEqualTo(6);
        assertThat(provision.period()).isEqualTo(30);
        assertThat(Totp.code(provision.secret(), provision.algorithm(), provision.digits(), provision.period(), Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.code(provision.secret(), "SHA1", 6, 30, Instant.ofEpochSecond(60))).isNotEqualTo("287082");
        assertThat(provision.toString()).doesNotContain(provision.secret());
        assertThat(Totp.parse("gezd gnbv-gy3tqojqgezdgnbvgy3tqojq").secret()).isEqualTo(provision.secret());
        assertThat(Totp.parse("otpauth://totp/team?secret=" + provision.secret() + "&algorithm=SHA256&digits=8&period=60").period()).isEqualTo(60);
    }
    @ParameterizedTest @ValueSource(strings = {
        "123456", "otpauth-migration://offline?data=something", "otpauth://hotp/team?secret=JBSWY3DPEHPK3PXP&counter=1",
        "otpauth://totp/team?secret=JBSWY3DPEHPK3PXP&secret=JBSWY3DPEHPK3PXP",
        "otpauth://totp/team?secret=JBSWY3DPEHPK3PXP&algorithm=MD5", "otpauth://totp/team?secret=JBSWY3DPEHPK3PXP&period=0",
        "otpauth://totp/team?secret=JBSWY3DPEHPK3PXP&digits=7", "otpauth://totp/team?secret=JBSWY3DPEHPK3PXP#fragment",
        "https://example.test/?secret=JBSWY3DPEHPK3PXP", "AAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAB"
    })
    void rejectsUnsupportedOrAmbiguousSecretsWithoutReflectingInput(String value) {
        assertThatThrownBy(() -> Totp.parse(value)).hasMessageNotContaining(value);
    }
    private static String base32(String ascii) {
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        StringBuilder bits = new StringBuilder();
        for (byte b : bytes) bits.append(String.format("%8s", Integer.toBinaryString(b & 255)).replace(' ', '0'));
        while (bits.length() % 5 != 0) bits.append('0');
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < bits.length(); i += 5) result.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt(Integer.parseInt(bits.substring(i, i + 5), 2)));
        return result.toString();
    }
}
