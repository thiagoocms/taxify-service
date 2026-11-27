package br.com.cshub.taxify.integration.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E-mail do Taxify (F1-RN-17), {@code taxify.mail.*} no application.yaml. {@code provider}: LOG só registra
 * (desenvolvimento e testes, nunca envia); SES envia pela role IAM da EC2 - nenhuma credencial aqui.
 */
@Component
@ConfigurationProperties(prefix = "taxify.mail")
public class MailProperties {

    /** TAXIFY_MAIL_PROVIDER - SES ou LOG. */
    private String provider = "LOG";
    /** TAXIFY_MAIL_FROM - remetente verificado no SES. */
    private String from = "nao-responda@cshub.com.br";
    /** TAXIFY_MAIL_SES_REGION. */
    private String sesRegion = "us-east-2";
    /** TAXIFY_MAIL_LOG_BODY - só desenvolvimento: o LOG mostra o corpo (com o link de verificação). */
    private boolean logBody = false;
    /**
     * TAXIFY_MAIL_RECIPIENT_ALLOWLIST - domínios ou endereços (vírgula); vazia = sem filtro. Fora dela o destinatário é
     * descartado antes do envio (AV-RN-13) - para testar com o SES no sandbox.
     */
    private List<String> recipientAllowlist = new ArrayList<>();
    /** TAXIFY_MAIL_SEND_INTERVAL_MS - intervalo mínimo entre dois envios (AV-RN-09); 0 = sem espera. */
    private long sendIntervalMs = 1000;

    public List<String> getRecipientAllowlist() {
        return recipientAllowlist;
    }

    public void setRecipientAllowlist(List<String> recipientAllowlist) {
        this.recipientAllowlist = recipientAllowlist == null ? new ArrayList<>() : recipientAllowlist;
    }

    public long getSendIntervalMs() {
        return sendIntervalMs;
    }

    public void setSendIntervalMs(long sendIntervalMs) {
        this.sendIntervalMs = sendIntervalMs;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getSesRegion() {
        return sesRegion;
    }

    public void setSesRegion(String sesRegion) {
        this.sesRegion = sesRegion;
    }

    public boolean isLogBody() {
        return logBody;
    }

    public void setLogBody(boolean logBody) {
        this.logBody = logBody;
    }
}
