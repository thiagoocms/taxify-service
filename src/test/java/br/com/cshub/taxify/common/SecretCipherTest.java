package br.com.cshub.taxify.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static String key(int seed) {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * seed + 7);
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTripsBytesAndText() {
        SecretCipher cipher = new SecretCipher(key(3));
        byte[] plain = "conteudo do pfx".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = cipher.encrypt(plain);

        assertThat(sealed).isNotEqualTo(plain);
        assertThat(cipher.decrypt(sealed)).isEqualTo(plain);
        assertThat(cipher.decryptText(cipher.encryptText("senha-123"))).isEqualTo("senha-123");
    }

    @Test
    void sameInputGivesDifferentOutputEachTime() {
        SecretCipher cipher = new SecretCipher(key(3));

        assertThat(cipher.encryptText("senha")).isNotEqualTo(cipher.encryptText("senha"));
    }

    @Test
    void anotherKeyCannotOpenIt() {
        String sealed = new SecretCipher(key(3)).encryptText("senha");

        assertThatThrownBy(() -> new SecretCipher(key(5)).decryptText(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutKeyItIsNotConfiguredAndRefusesToEncrypt() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encryptText("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyOfTheWrongSizeFailsAtStartup() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
