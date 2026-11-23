package br.com.cshub.taxify.dto.fiscalxml;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The recipient's manifestation screen: notes still owing one, and sending it in batch. */
public final class ManifestationDTOs {

    private ManifestationDTOs() {
    }

    /**
     * An entry without a conclusive manifestation (confirmation, unknown or not performed).
     *
     * @param notaId      the stored note - null when SEFAZ only gave the summary so far
     * @param xmlCompleto false = only the summary (resNFe) is here
     * @param ciencia     the Ciência da Operação is registered for it
     * @param prazo       last day to manifest: 180 days after the authorization (or the issue, when unknown)
     */
    public record Note(
            String chave,
            UUID notaId,
            String numero,
            String serie,
            String emitenteNome,
            String emitenteDocumento,
            LocalDateTime dataEmissao,
            BigDecimal valorTotal,
            boolean xmlCompleto,
            boolean ciencia,
            LocalDate prazo,
            long diasRestantes
    ) {
    }

    /**
     * @param habilitada  the plan has the manifestation service (and the server allows it)
     * @param certificado the company has a valid A1 to sign with
     */
    public record Pending(boolean habilitada, boolean certificado, List<Note> notas) {
    }

    /**
     * @param tipo          CIENCIA, CONFIRMACAO, DESCONHECIMENTO or NAO_REALIZADA
     * @param justificativa required for NAO_REALIZADA, 15 to 255 characters
     */
    public record Request(@NotBlank String companyId, @NotBlank String tipo, String justificativa,
                          @NotEmpty List<String> chaves) {
    }

    /** What SEFAZ answered for each note. */
    public record Result(int registradas, int recusadas, List<Item> itens) {

        public record Item(String chave, boolean ok, String codigo, String mensagem) {
        }
    }
}
