package br.com.cshub.taxify.integration.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code TAXIFY_MAIL_PROVIDER=LOG}: não envia nada, só registra que um e-mail sairia - remetente, assunto e quantos
 * destinatários (o endereço não, é dado pessoal). O corpo só aparece com {@code logBody} (desenvolvimento, para abrir o
 * link de verificação); nunca em produção, onde o provider é SES (F1-RN-17).
 */
public class LogMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LogMailSender.class);

    private final String from;
    private final boolean logBody;

    public LogMailSender(String from, boolean logBody) {
        this.from = from;
        this.logBody = logBody;
    }

    @Override
    public void send(MailMessage message) {
        log.info("mail.log from={} recipients={} subject=\"{}\"", from, message.to().size(), message.subject());
        if (logBody) {
            log.info("mail.log body:\n{}", message.body());
        }
    }

    @Override
    public String from() {
        return from;
    }
}
