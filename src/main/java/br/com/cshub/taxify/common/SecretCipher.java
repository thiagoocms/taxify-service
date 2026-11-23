package br.com.cshub.taxify.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for secrets the app must read back - the A1 certificates (.pfx) and their
 * passwords. The key is {@code taxify.secrets.key} (env {@code CERT_ENCRYPTION_KEY}, 32 bytes
 * in base64 - {@code openssl rand -base64 32}) and never touches the database or the bucket,
 * so a leaked dump or bucket alone opens nothing. Output: 12-byte random IV + ciphertext + tag.
 * Losing the key means uploading the certificates again.
 */
@Component
public class SecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${taxify.secrets.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.strip());
        if (raw.length != 32) {
            throw new IllegalStateException("taxify.secrets.key (CERT_ENCRYPTION_KEY) must be 32 bytes in base64 - openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_LENGTH + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the secret", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            return cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt the secret - was CERT_ENCRYPTION_KEY changed?", e);
        }
    }

    public String encryptText(String plain) {
        return Base64.getEncoder().encodeToString(encrypt(plain.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptText(String sealed) {
        return new String(decrypt(Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8);
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException("taxify.secrets.key (CERT_ENCRYPTION_KEY) is not set");
        }
        return key;
    }
}
