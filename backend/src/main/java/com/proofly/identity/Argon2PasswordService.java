package com.proofly.identity;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class Argon2PasswordService {

    private static final int MEMORY_KB = 65536; // 64 MiB
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int HASH_LEN = 32;
    private static final int SALT_LEN = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String rawSecret) {
        byte[] salt = new byte[SALT_LEN];
        secureRandom.nextBytes(salt);
        byte[] hash = computeArgon2id(rawSecret, salt, MEMORY_KB, ITERATIONS, PARALLELISM);
        return "$argon2id$v=19$m=" + MEMORY_KB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    public boolean matches(String rawSecret, String encodedHash) {
        if (rawSecret == null || encodedHash == null || !encodedHash.startsWith("$argon2id$")) {
            return false;
        }
        String[] parts = encodedHash.split("\\$");
        if (parts.length != 6) {
            return false;
        }
        String[] params = parts[3].split(",");
        int memory = Integer.parseInt(params[0].substring(2));
        int iterations = Integer.parseInt(params[1].substring(2));
        int parallelism = Integer.parseInt(params[2].substring(2));
        byte[] salt = Base64.getDecoder().decode(parts[4]);
        byte[] expected = Base64.getDecoder().decode(parts[5]);
        byte[] actual = computeArgon2id(rawSecret, salt, memory, iterations, parallelism);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] computeArgon2id(String rawSecret, byte[] salt, int memoryKb, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memoryKb)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] result = new byte[HASH_LEN];
        generator.generateBytes(rawSecret.getBytes(StandardCharsets.UTF_8), result, 0, result.length);
        return result;
    }
}
