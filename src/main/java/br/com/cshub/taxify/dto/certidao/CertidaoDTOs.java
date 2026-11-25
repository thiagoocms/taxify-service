package br.com.cshub.taxify.dto.certidao;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Certidão SEFAZ-PE (M5): what the carteira and the company's page show. */
public final class CertidaoDTOs {

    private CertidaoDTOs() {
    }

    /** One certidão, as read from its PDF. {@code status}: VALIDA, A_VENCER or VENCIDA. */
    public record Certidao(UUID id, String uf, String tipo, String tipoLabel, String numero, String situacao,
                           String situacaoLabel, LocalDate emitidaEm, LocalDate validaAte, String status, long diasRestantes,
                           String origem, String fileName, LocalDateTime importedAt, String importedBy, boolean hasPdf) {
    }

    /** The carteira's column for one company: {@code status} SEM_CERTIDAO when there is none yet. */
    public record Summary(UUID companyId, String status, String situacao, String situacaoLabel, LocalDate validaAte,
                          UUID certidaoId, boolean hasPdf) {
    }

    /** The last issue attempt (scheduled or by the button). */
    public record LastRun(LocalDateTime at, String status, String detail) {
    }

    /**
     * The company's section: whether the certidão applies ({@code motivo} when it doesn't), the newest one, the older
     * ones and the last attempt - with the e-Fisco page where a person issues it.
     */
    public record CompanyCertidao(boolean aplicavel, String motivo, Certidao atual, List<Certidao> historico,
                                  LastRun ultimaTentativa, String paginaEmissao) {
    }

    /** "Emitir agora": {@code status} OK (issued and kept) or ERRO, with why (captcha...). */
    public record IssueResult(String status, String detail, String paginaEmissao, CompanyCertidao certidao) {
    }
}
