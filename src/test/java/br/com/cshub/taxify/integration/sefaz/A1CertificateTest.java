package br.com.cshub.taxify.integration.sefaz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A1CertificateTest {

    /** Self-signed test certificate, CN "EMPRESA TESTE LTDA:11222333000181", password teste123. */
    static byte[] testPfx() throws IOException {
        try (InputStream in = A1CertificateTest.class.getResourceAsStream("/certificates/a1-teste.pfx")) {
            return in.readAllBytes();
        }
    }

    @Test
    void opensWithThePasswordAndReadsTheEcnpjSubject() throws Exception {
        A1Certificate certificate = A1Certificate.open(testPfx(), "teste123");

        assertThat(certificate.cnpj()).isEqualTo("11222333000181");
        assertThat(certificate.subjectName()).contains("EMPRESA TESTE LTDA");
        assertThat(certificate.validUntil()).isAfter(LocalDateTime.now());
        assertThat(certificate.sslContext()).isNotNull();
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThatThrownBy(() -> A1Certificate.open(testPfx(), "errada")).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void somethingElseIsRejected() {
        assertThatThrownBy(() -> A1Certificate.open("not a pfx".getBytes(), "x")).isInstanceOf(GeneralSecurityException.class);
    }
}
