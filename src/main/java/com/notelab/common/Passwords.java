package com.notelab.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** 与 Python 版一致的密码哈希：pbkdf2$<salt_hex>$<dk_hex>，PBKDF2-HMAC-SHA256，120000 轮。 */
public final class Passwords {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    private static byte[] pbkdf2(String password, String salt) {
        try {
            // Python: hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 120_000)
            // salt 以 hex 字符串的 UTF-8 字节作为盐（与 Python 的 salt.encode() 一致）
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(java.nio.charset.StandardCharsets.UTF_8), 120_000, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 计算失败", e);
        }
    }

    public static String hash(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes); // secrets.token_hex(16)
        String dk = HexFormat.of().formatHex(pbkdf2(password, salt));
        return "pbkdf2$" + salt + "$" + dk;
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split("\\$", 3);
            if (parts.length != 3) return false;
            String salt = parts[1];
            String hexd = parts[2];
            byte[] dk = pbkdf2(password, salt);
            return MessageDigest.isEqual(HexFormat.of().formatHex(dk).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    hexd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
