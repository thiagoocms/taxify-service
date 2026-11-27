package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.integration.mail.MailMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AV-RN-06: CT-AV-01, CT-AV-02, CT-AV-09, CT-AV-10, CT-AV-11, CA-AV-06. Dados fictícios. */
class PendencyAlertMailTemplateTest {

    private static final UUID X = UUID.randomUUID();
    private static final UUID Y = UUID.randomUUID();
    private static final UUID Z = UUID.randomUUID();
    private static final LocalDateTime SEEN = LocalDateTime.of(2026, 11, 30, 10, 40);

    private static PendencyAlertCandidate item(UUID company, String name, PendencyType type) {
        return new PendencyAlertCandidate(UUID.randomUUID(), company, name, type, SEEN);
    }

    /** CT-AV-01: Carla - 3 companies, 14 new. */
    private static List<PendencyAlertCandidate> carla() {
        List<PendencyAlertCandidate> items = new ArrayList<>();
        items.add(item(X, "Têxtil Exemplo Ltda", PendencyType.CERTIFICATE_EXPIRED));
        for (int i = 0; i < 12; i++) {
            items.add(item(Y, "Confecção Modelo ME", PendencyType.MANIFESTATION_DEADLINE));
        }
        items.add(item(Z, "Comércio Teste Ltda", PendencyType.SEFAZ_CAPTURE_ERROR));
        return items;
    }

    private static MailMessage build(List<PendencyAlertCandidate> items, long total, int max) {
        return PendencyAlertMailTemplate.build(new PendencyAlertMailTemplate.Digest("carla@exemplo.test", "Carla Admin Fictícia",
                "Escritório Exemplo", items, total, "https://taxify.exemplo.test/", max));
    }

    @Test
    void theDigestListsCompaniesAlphabeticallyWithTypeCounts() {
        MailMessage message = build(carla(), 14, 30);

        assertThat(message.to()).containsExactly("carla@exemplo.test");
        assertThat(message.subject()).isEqualTo("Taxify: 14 pendência(s) alta(s) nova(s) na sua carteira");
        String body = message.body();
        assertThat(body).startsWith("Olá, Carla.").contains("Escritório Exemplo");
        assertThat(body).contains("- Comércio Teste Ltda: Captura SEFAZ com erro (1)")
                .contains("- Confecção Modelo ME: Prazo de manifestação perto do fim (12)")
                .contains("- Têxtil Exemplo Ltda: Certificado A1 vencido (1)");
        assertThat(body.indexOf("Comércio")).isLessThan(body.indexOf("Confecção"));
        assertThat(body.indexOf("Confecção")).isLessThan(body.indexOf("Têxtil"));
        assertThat(body).contains("https://taxify.exemplo.test/pendencias?severidade=ALTA&status=ABERTA")
                .contains("Minha conta > Avisos por e-mail: https://taxify.exemplo.test/minha-conta");
        assertThat(PendencyAlertMailTemplate.companiesCount(carla())).isEqualTo(3);
    }

    /** CA-AV-06: no company name in the subject. */
    @Test
    void theSubjectHasNoCompanyName() {
        MailMessage message = build(carla(), 14, 30);
        assertThat(message.subject()).doesNotContain("Têxtil").doesNotContain("Confecção").doesNotContain("Comércio");
    }

    /** Within a company the types go by count (largest first), as in the spec's example. */
    @Test
    void typesGoByCountWithinACompany() {
        List<PendencyAlertCandidate> items = new ArrayList<>();
        items.add(item(X, "Têxtil Exemplo Ltda", PendencyType.CERTIFICATE_EXPIRED));
        for (int i = 0; i < 12; i++) {
            items.add(item(X, "Têxtil Exemplo Ltda", PendencyType.MANIFESTATION_DEADLINE));
        }
        assertThat(build(items, 13, 30).body())
                .contains("Têxtil Exemplo Ltda: Prazo de manifestação perto do fim (12); Certificado A1 vencido (1)");
    }

    /** CT-AV-02: 13 new in the list and the 17 open in the portfolio. */
    @Test
    void theTotalCountsNewAndOld() {
        List<PendencyAlertCandidate> ana = carla().subList(0, 13);
        assertThat(build(ana, 17, 30).body()).contains("17 pendências altas abertas na sua carteira");
        assertThat(build(ana.subList(0, 1), 1, 30).body()).contains("1 pendência alta aberta na sua carteira");
    }

    /** CT-AV-09: the audit finding uses the label "Auditoria tipo malha". */
    @Test
    void theAuditFindingUsesItsLabel() {
        assertThat(build(List.of(item(X, "Têxtil Exemplo Ltda", PendencyType.AUDIT_FINDING)), 1, 30).body())
                .contains("Têxtil Exemplo Ltda: Auditoria tipo malha (1)");
    }

    /** CT-AV-10: 45 companies - 30 listed + "e mais 15 empresa(s)". */
    @Test
    void atMostThirtyCompaniesListed() {
        List<PendencyAlertCandidate> items = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            items.add(item(UUID.randomUUID(), "Empresa Fictícia %02d".formatted(i), PendencyType.CERTIFICATE_EXPIRED));
        }
        String body = build(items, 45, 30).body();
        assertThat(body.lines().filter(line -> line.startsWith("- Empresa Fictícia"))).hasSize(30);
        assertThat(body).contains("e mais 15 empresa(s)").contains("Empresa Fictícia 29").doesNotContain("Empresa Fictícia 30");
    }

    /** CT-AV-11 / CA-AV-06: only what the candidate carries - no title, detail, note number or third party. */
    @Test
    void nothingConfidentialGoesOut() {
        // the candidate has no title/detail by construction - the "NF 1234 de Fornecedor Fictício" never reaches here
        String body = build(List.of(item(X, "Têxtil Exemplo Ltda", PendencyType.MANIFESTATION_DEADLINE)), 1, 30).body();
        assertThat(body).doesNotContain("1234").doesNotContain("Fornecedor").doesNotContain("CNPJ").doesNotContain("R$");
        assertThat(PendencyAlertCandidate.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("pendencyId", "companyId", "companyName", "type", "firstSeenAt");
    }

    @Test
    void longCompanyNamesAreCutAtSixty() {
        String name = "Empresa Com Um Nome Muito Comprido Ficticio Para Testar O Limite De Sessenta Caracteres Ltda";
        String body = build(List.of(item(X, name, PendencyType.CERTIFICATE_EXPIRED)), 1, 30).body();
        assertThat(body).contains("- " + name.substring(0, 60).strip() + ": Certificado A1 vencido (1)");
        assertThat(PendencyAlertMailTemplate.companyName(name).length()).isLessThanOrEqualTo(60);
    }

    /** Review item 4: a MEI name carries the CNPJ root ("12.345.678 NOME") or a CPF at the end - it never goes out. */
    @Test
    void documentNumbersAreRemovedFromTheCompanyName() {
        assertThat(PendencyAlertMailTemplate.companyName("12.345.678 FULANA FICTICIA")).isEqualTo("FULANA FICTICIA");
        assertThat(PendencyAlertMailTemplate.companyName("12345678 FULANA FICTICIA")).isEqualTo("FULANA FICTICIA");
        assertThat(PendencyAlertMailTemplate.companyName("12.345.678/0001-90 - Comércio Fictício")).isEqualTo("Comércio Fictício");
        assertThat(PendencyAlertMailTemplate.companyName("FULANA FICTICIA 123.456.789-09")).isEqualTo("FULANA FICTICIA");
        assertThat(PendencyAlertMailTemplate.companyName("FULANA FICTICIA - 12345678909")).isEqualTo("FULANA FICTICIA");
        // in the middle too (QA): "JOAO 12.345.678 SILVA" would leak the CNPJ root
        assertThat(PendencyAlertMailTemplate.companyName("JOAO 12.345.678 SILVA")).isEqualTo("JOAO SILVA");
        assertThat(PendencyAlertMailTemplate.companyName("JOAO - 12345678000190 - SILVA LTDA")).isEqualTo("JOAO - SILVA LTDA");
        assertThat(PendencyAlertMailTemplate.companyName("Loja 12 Fictícia 123.456.789-09 Centro")).isEqualTo("Loja 12 Fictícia Centro");
        // short numbers are part of the name
        assertThat(PendencyAlertMailTemplate.companyName("3M do Brasil Fictícia")).isEqualTo("3M do Brasil Fictícia");
        assertThat(PendencyAlertMailTemplate.companyName("2001 Comércio Fictício")).isEqualTo("2001 Comércio Fictício");
        assertThat(PendencyAlertMailTemplate.companyName("Loja Fictícia 24")).isEqualTo("Loja Fictícia 24");
        assertThat(PendencyAlertMailTemplate.companyName("12.345.678")).isEqualTo("Empresa sem nome");

        String body = build(List.of(item(X, "12.345.678 FULANA FICTICIA", PendencyType.CERTIFICATE_EXPIRED)), 1, 30).body();
        assertThat(body).contains("- FULANA FICTICIA: Certificado A1 vencido (1)").doesNotContain("12.345.678").doesNotContain("12345678");
    }
}
