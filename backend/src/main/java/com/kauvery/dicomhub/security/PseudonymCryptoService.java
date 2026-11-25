package com.kauvery.dicomhub.security;

import com.kauvery.dicomhub.config.HubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * On-premise AES-256-GCM encryption for the pseudonym vault (FR-12, NFR-06)
 * and SHA-256 integrity checksums for the Original DICOM Repository.
 */
@Service
public class PseudonymCryptoService {

    private static final String AES_GCM_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PseudonymCryptoService(HubProperties properties) {
        byte[] rawKey = Base64.getDecoder().decode(properties.crypto().pseudonymKeyBase64());
        if (rawKey.length != 32) {
            byte[] normalized = sha256Bytes(rawKey);
            this.secretKey = new SecretKeySpec(normalized, "AES");
        } else {
            this.secretKey = new SecretKeySpec(rawKey, "AES");
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return "";
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt pseudonym mapping with AES-256-GCM", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            return "";
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.length);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt pseudonym mapping with AES-256-GCM", e);
        }
    }

    public String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256Bytes(data));
    }

    public String sha256Hex(String value) {
        if (value == null) {
            return "";
        }
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256Bytes(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
