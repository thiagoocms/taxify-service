package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.integration.mail.MailMessage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Textos dos e-mails de senha (SN-RN-03 e SN-RN-12). Só login, escritório, data e link - nada de dado de cliente. O
 * link de redefinição leva o token em claro no <b>fragmento</b> ({@code #token=}), que o navegador não manda ao
 * servidor (fora do log de acesso do nginx e do Caddy e do Referer); o corpo nunca vai para o log em produção
 * ({@code TAXIFY_MAIL_LOG_BODY=false}).
 */
public final class PasswordMailTemplates {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private PasswordMailTemplates() {
    }

    public static String resetLink(String publicUrl, String token) {
        return stripSlash(publicUrl) + "/auth/redefinir-senha#token=" + token;
    }

    public static MailMessage reset(String to, String login, String firmName, String link, int validMinutes) {
        String body = """
                Olá!

                Recebemos um pedido para redefinir a senha do login %s no Taxify (escritório %s).

                Para definir uma senha nova, abra o link abaixo:
                %s

                O link vale por %d minutos e pode ser usado uma vez só. Se expirar, peça um novo na tela de login.

                Se você não pediu, ignore esta mensagem: a sua senha continua a mesma.

                Taxify - CS Hub
                """.formatted(login, firmName == null ? "" : firmName, link, validMinutes);
        return new MailMessage(List.of(to), "Redefinição de senha do Taxify", body);
    }

    public static MailMessage changed(String to, String login, LocalDateTime changedAt, PasswordChangeChannel channel) {
        String how = switch (channel) {
            case RESET -> "por você pela redefinição por e-mail";
            case SELF -> "por você em Minha conta";
            case ADMIN -> "pelo administrador do escritório";
        };
        String body = """
                Olá!

                A senha do seu login %s no Taxify foi alterada em %s (horário de Recife) %s.

                Se não foi você, fale com o administrador do seu escritório.

                Taxify - CS Hub
                """.formatted(login, DATE_TIME.format(changedAt), how);
        return new MailMessage(List.of(to), "A senha do seu Taxify foi alterada", body);
    }

    private static String stripSlash(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
