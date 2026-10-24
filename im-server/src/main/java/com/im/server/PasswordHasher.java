package com.im.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希工具：PBKDF2WithHmacSHA256 + 随机盐。
 *
 * <p>存储格式为 {@code iterations:saltBase64:hashBase64}，便于将来升级迭代次数。</p>
 */
public final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private PasswordHasher() {
    }

    /** 生成随机盐（Base64）。 */
    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** 计算哈希。 */
    public static String hash(String password, String saltBase64, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    Base64.getDecoder().decode(saltBase64), iterations, HASH_BITS);
            byte[] digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 计算失败", e);
        }
    }

    /** 恒定时间比较（避免时序侧信道）。 */
    public static boolean verify(String password, String saltBase64, int iterations, String expected) {
        String actual = hash(password, saltBase64, iterations);
        return java.security.MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
