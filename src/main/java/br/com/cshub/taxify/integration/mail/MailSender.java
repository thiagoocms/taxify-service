package br.com.cshub.taxify.integration.mail;

/**
 * Envio de e-mail do Taxify (F1-RN-17). Duas implementações, escolhidas por {@code taxify.mail.provider}
 * (TAXIFY_MAIL_PROVIDER): {@code SES} em produção (credencial pela role IAM da EC2) e {@code LOG} em desenvolvimento
 * e testes, que nunca envia. O remetente é {@code taxify.mail.from} (TAXIFY_MAIL_FROM).
 */
public interface MailSender {

    /** @throws MailSendException quando o envio falha - quem chama registra e tenta de novo na próxima rodada */
    void send(MailMessage message);

    /** Remetente configurado. */
    String from();

    /**
     * Se um e-mail a este endereço sairia de fato ({@code TAXIFY_MAIL_RECIPIENT_ALLOWLIST}, AV-RN-13). Rotinas de lote
     * consultam antes de reservar o envio, para não marcar como enviado o que seria descartado.
     */
    default boolean accepts(String address) {
        return true;
    }
}
