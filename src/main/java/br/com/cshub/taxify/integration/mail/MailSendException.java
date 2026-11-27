package br.com.cshub.taxify.integration.mail;

/**
 * O e-mail não saiu (SES fora, remetente não verificado, destinatário recusado...). Sem causa de propósito: a exceção
 * do provedor pode trazer endereços de e-mail no texto, e quem pega esta aqui loga a mensagem.
 */
public class MailSendException extends RuntimeException {

    public MailSendException(String message) {
        super(message);
    }
}
