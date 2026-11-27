package br.com.cshub.taxify.dto.capture;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Uma empresa no painel da captura (CP-RN-09). Nunca traz o CNPJ: só o número do estabelecimento ({@code 0001}).
 * {@code firmId}/{@code firmName} só na visão da plataforma. {@code stages}, {@code lastDocuments},
 * {@code recoveringMonths} e {@code memory} só no detalhe ({@code GET .../companies/{id}}); nulos na lista.
 */
public record CaptureCompanyDTO(UUID companyId, String companyName, String establishment, boolean active,
                                UUID firmId, String firmName,
                                String status, String statusReason,
                                Channels channels,
                                LastFailure lastFailure,
                                List<Stage> stages,
                                LastDocuments lastDocuments,
                                List<String> recoveringMonths,
                                Memory memory) {

    public record Channels(@JsonProperty("SIEG") Sieg sieg, @JsonProperty("SEFAZ") Direct sefaz,
                           @JsonProperty("ADN") Direct adn) {
    }

    /**
     * @param queuePosition       posição na fila (0 = a próxima); com pedido aberto, a posição na fila de prioridade
     * @param nextAttemptEstimate estimativa da próxima tentativa
     */
    public record Sieg(String status, String reason, OffsetDateTime lastAttemptAt, OffsetDateTime verifiedAt,
                       Integer queuePosition, OffsetDateTime nextAttemptEstimate, Side received, Side issued,
                       EventsCheckedOn eventsCheckedOn, LocalDate serviceInvoicesCheckedOn, Priority priorityRequest) {
    }

    /**
     * Um lado (recebidas ou emitidas). {@code imported} é "no Taxify (qualquer origem)" - a contagem do RN-09 da
     * captura; {@code month} é o mês a que os contadores se referem.
     */
    /**
     * One SIEG side of the window. {@code imported}: the notes SIEG itself delivered (B1) - with {@code totalSieg} they
     * make {@code missing} and the situation; {@code storedAnySource}: the side's notes of the window in Taxify from any
     * source (SIEG, SEFAZ, upload) at the same check - shown next to them; null until the side's first check after V85.
     */
    public record Side(int imported, Integer totalSieg, LocalDate from, LocalDate to, OffsetDateTime checkedAt, String month,
                       Integer missing, PreviousMonth previousMonth, String memory, Integer storedAnySource) {
    }

    public record PreviousMonth(String month, Integer imported, Integer totalSieg, Integer missing, String memory) {
    }

    public record EventsCheckedOn(LocalDate received, LocalDate issued) {
    }

    /**
     * Pedido de "Sincronizar agora" em aberto. {@code requestedByName}: nome de quem pediu, só para quem é do mesmo
     * escritório (null quando pedido pela plataforma ou visto de fora); {@code requestedByLabel}: o texto pronto ("Pedido
     * por {nome} às {HH:mm}" / "Pedido pela CS Hub às {HH:mm}"). {@code position} começa em 0.
     */
    public record Priority(UUID requestId, String status, OffsetDateTime requestedAt, String requestedByName,
                           boolean requestedByPlatform, int position, OffsetDateTime estimatedAt, String requestedByLabel) {
    }

    /** SEFAZ ou ADN. {@code failureCode}/{@code failureMessage} quando COM_ERRO. Nunca o {@code last_detail}. */
    public record Direct(String status, String reason, OffsetDateTime lastRunAt, String lastStatus, OffsetDateTime nextQueryAt,
                         Certificate certificate, String failureCode, String failureMessage) {
    }

    /** {@code expiringSoon}: vence nos próximos 30 dias; {@code warning}: o texto "vence em dd/MM/aaaa". */
    public record Certificate(boolean present, boolean fromHeadOffice, LocalDate validUntil, boolean expired,
                              boolean expiringSoon, String warning) {
    }

    /**
     * @param explainsStatus a falha aberta cuja mensagem já é o {@code statusReason} da empresa: a lista mostra o texto uma
     *                       vez só (o front esconde {@code message} quando true)
     */
    public record LastFailure(String stage, OffsetDateTime at, String code, String providerStatus, String message,
                              boolean resolved, int consecutiveFailures, OffsetDateTime lastSuccessAt, String memory,
                              boolean explainsStatus) {
    }

    /** As memórias prontas do detalhe (CP-RN-09): fila, verificação completa e recuperação. */
    public record Memory(String queue, String verification, String recovery) {
    }

    /** Última nota gravada por tipo e origem ({@code created_date}); null = "Nunca". */
    public record LastDocuments(Nfe nfe, Nfse nfse) {
    }

    public record Nfe(@JsonProperty("SIEG") OffsetDateTime sieg, @JsonProperty("SEFAZ_DIST") OffsetDateTime sefazDist,
                      @JsonProperty("UPLOAD") OffsetDateTime upload) {
    }

    public record Nfse(@JsonProperty("SIEG") OffsetDateTime sieg, @JsonProperty("ADN") OffsetDateTime adn,
                       @JsonProperty("UPLOAD") OffsetDateTime upload) {
    }

    public record Stage(String stage, String lastOutcome, OffsetDateTime lastOutcomeAt, OffsetDateTime lastSuccessAt,
                        OffsetDateTime lastFailureAt, String failureCode, String providerStatus, String failureMessage,
                        boolean resolved, int consecutiveFailures, String warningCode, Integer warningCount,
                        String warningMessage, String memory) {
    }
}
