package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.FirmNotificationType;
import br.com.cshub.taxify.integration.mail.MailMessage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Textos dos e-mails da fase 1. Só o nome do escritório, a data e o link do Taxify - nenhum dado de cliente
 * (F1-RN-16). O link de verificação leva o token em claro: o corpo nunca vai para o log em produção (F1-RN-17).
 */
public final class FirmMailTemplates {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private FirmMailTemplates() {
    }

    public static String verificationLink(String publicUrl, String token) {
        return stripSlash(publicUrl) + "/auth/verificar?token=" + token;
    }

    public static MailMessage verification(List<String> to, String firmName, String link, int validHours) {
        String body = """
                Olá!

                Recebemos o cadastro do escritório %s no Taxify.

                Para confirmar o seu e-mail e começar o teste grátis, abra o link abaixo:
                %s

                O link vale por %d horas e pode ser usado uma vez só. Se expirar, peça um novo na tela de login.

                Se você não fez este cadastro, ignore esta mensagem.

                Taxify - CS Hub
                """.formatted(firmName, link, validHours);
        return new MailMessage(to, "Confirme seu e-mail para começar o teste do Taxify", body);
    }

    public static MailMessage trialNotice(List<String> to, String firmName, FirmNotificationType type, LocalDate trialEndsOn,
                                          String publicUrl) {
        String when = switch (type) {
            case TRIAL_D3 -> "termina em 3 dias, em " + DATE.format(trialEndsOn);
            case TRIAL_D1 -> "termina amanhã, " + DATE.format(trialEndsOn);
            case TRIAL_D0 -> "termina hoje, " + DATE.format(trialEndsOn);
        };
        String subject = switch (type) {
            case TRIAL_D3 -> "Seu teste do Taxify termina em 3 dias";
            case TRIAL_D1 -> "Seu teste do Taxify termina amanhã";
            case TRIAL_D0 -> "Seu teste do Taxify termina hoje";
        };
        String body = """
                Olá!

                O teste grátis do Taxify do escritório %s %s.

                Depois disso o escritório fica em somente leitura: os dados continuam disponíveis para consulta e
                exportação, mas não dá para criar nem editar, e as capturas automáticas param.

                Para continuar usando o Taxify, fale com a CS Hub.

                %s

                Taxify - CS Hub
                """.formatted(firmName, when, stripSlash(publicUrl));
        return new MailMessage(to, subject, body);
    }

    private static String stripSlash(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
