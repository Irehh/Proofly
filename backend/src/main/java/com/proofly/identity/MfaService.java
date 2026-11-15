package com.proofly.identity;

import com.proofly.audit.AuditWriter;
import com.proofly.common.ErrorCode;
import com.proofly.common.PiiCrypto;
import com.proofly.common.ProoflyException;
import com.proofly.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * TOTP MFA hooks (RFC 6238) ready for ADMIN and SUPER_ADMIN accounts.
 */
@Service
public class MfaService {

    public record MfaSetupResponse(String base32Secret, String otpauthUri) {}

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final JdbcClient tenantJdbc;
    private final PiiCrypto piiCrypto;
    private final AuditWriter auditWriter;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaService(DataSource tenantRoutingDataSource, PiiCrypto piiCrypto, AuditWriter auditWriter) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.piiCrypto = piiCrypto;
        this.auditWriter = auditWriter;
    }

    public MfaSetupResponse beginSetup(UUID userId, String accountLabel) {
        TenantContext.requireCurrent();
        byte[] secretBytes = new byte[20];
        secureRandom.nextBytes(secretBytes);
        String base32 = encodeBase32(secretBytes);
        byte[] encrypted = piiCrypto.encrypt(base32);

        tenantJdbc.sql("UPDATE users SET mfa_secret_encrypted = :sec WHERE id = :uid")
                .param("sec", encrypted)
                .param("uid", userId)
                .update();

        String uri = "otpauth://totp/Proofly:" + accountLabel + "?secret=" + base32 + "&issuer=Proofly";
        return new MfaSetupResponse(base32, uri);
    }

    public void enableMfa(UUID userId, UUID membershipId, String totpCode, String ipAddress) {
        TenantContext.requireCurrent();
        byte[] encrypted = tenantJdbc.sql("SELECT mfa_secret_encrypted FROM users WHERE id = :uid")
                .param("uid", userId)
                .query(byte[].class)
                .optional()
                .orElseThrow(() -> new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                        "MFA setup has not been initiated"));

        String base32 = piiCrypto.decrypt(encrypted);
        if (!verifyTotp(base32, totpCode)) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid TOTP verification code");
        }

        tenantJdbc.sql("UPDATE users SET mfa_enabled = TRUE WHERE id = :uid")
                .param("uid", userId)
                .update();

        auditWriter.writeTenantEvent(membershipId, "MFA_ENABLED", "user", userId.toString(),
                Map.of("mfaEnabled", false), Map.of("mfaEnabled", true), ipAddress, null);
    }

    public boolean verifyTotp(String base32Secret, String code) {
        if (code == null || !code.matches("^\\d{6}$")) {
            return false;
        }
        byte[] key = decodeBase32(base32Secret);
        long currentStep = Instant.now().getEpochSecond() / 30L;
        for (long offset = -1; offset <= 1; offset++) {
            String candidate = generateCode(key, currentStep + offset);
            if (candidate.equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static String generateCode(byte[] key, long counter) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hmac = mac.doFinal(data);
            int offset = hmac[hmac.length - 1] & 0x0F;
            int binary = ((hmac[offset] & 0x7F) << 24)
                    | ((hmac[offset + 1] & 0xFF) << 16)
                    | ((hmac[offset + 2] & 0xFF) << 8)
                    | (hmac[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }

    private static byte[] decodeBase32(String input) {
        String clean = input.replace("=", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (char c : clean.toCharArray()) {
            int val = (c >= 'A' && c <= 'Z') ? (c - 'A') : (c - '2' + 26);
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
