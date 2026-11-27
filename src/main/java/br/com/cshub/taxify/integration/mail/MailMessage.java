package br.com.cshub.taxify.integration.mail;

import java.util.List;

/**
 * Um e-mail a enviar, só texto. Nunca leva dado fiscal de cliente (F1-RN-16). O corpo pode ter um segredo (o link de
 * verificação): por isso nenhuma implementação o registra no log, a não ser o LOG com {@code taxify.mail.log-body=true}
 * em desenvolvimento (F1-RN-17).
 */
public record MailMessage(List<String> to, String subject, String body) {

    public MailMessage {
        to = to == null ? List.of() : List.copyOf(to);
    }
}
