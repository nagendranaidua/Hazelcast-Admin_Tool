package com.genius.hz.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for SSH credentials, Hazelcast keystore passwords,
 * and per-cluster usernames/passwords stored at rest in Postgres.
 *
 * Master key (KEK) is supplied via the ADMIN_KEK env var (or -Dhz-admin.security.kek=).
 * In dev a fallback default is provided so the app boots; production deployments MUST
 * override it (see README — startup will warn if running with the default).
 *
 * Cipher format (base64): 12-byte IV || 16-byte tag || ciphertext
 */
@Service
public class CryptoService {

    private static final int IV_BYTES  = 12;
    private static final int TAG_BITS  = 128;
    private static final String DEFAULT_KEK = "dev-only-32byte-key-please-rotate!!";

    private final SecretKeySpec key;
    private final boolean       usingDefault;

    public CryptoService(@Value("${hz-admin.security.kek}") String kek) {
        this.usingDefault = DEFAULT_KEK.equals(kek);
        // Stretch any-length input to 32 bytes (AES-256) via SHA-256
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(kek.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("KEK init failed", e);
        }
    }

    public boolean isUsingDefaultKek() { return usingDefault; }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(String cipherB64) {
        if (cipherB64 == null) return null;
        try {
            byte[] in = Base64.getDecoder().decode(cipherB64);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(in, 0, iv, 0, IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed (wrong KEK?)", e);
        }
    }
}
