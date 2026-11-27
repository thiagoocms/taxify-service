package br.com.cshub.taxify.dto.notification;

/**
 * O resumo diário das pendências ALTA (AV-RN-02, AV-RN-08): {@code enabled} é a preferência (gravada ou o padrão);
 * {@code eligible=false} quando o usuário não tem acesso às pendências ({@code INVOICES_VIEW}) - o front mostra a
 * opção desabilitada com o motivo, e nada chega mesmo com {@code enabled=true}.
 */
public record PendencyHighDigestSettingDTO(boolean enabled, boolean eligible) {
}
