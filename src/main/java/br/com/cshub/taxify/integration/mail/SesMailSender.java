package br.com.cshub.taxify.integration.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.nio.charset.StandardCharsets;

/**
 * {@code TAXIFY_MAIL_PROVIDER=SES}: envia pelo Amazon SES (API v2). A credencial é a da role IAM da EC2 (cadeia padrão
 * do SDK, nada no código nem no .env); o domínio e o remetente são verificados no SES pela CS Hub (I1-1). O cliente tem
 * timeout e as novas tentativas com espera do próprio SDK; uma falha vira {@link MailSendException} só com a classe, o
 * código da AWS, o status e o requestId - nunca o texto da AWS, que lista os endereços recusados.
 */
public class SesMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SesMailSender.class);

    private final SesV2Client client;
    private final String from;

    public SesMailSender(SesV2Client client, String from) {
        this.client = client;
        this.from = from;
    }

    @Override
    public void send(MailMessage message) {
        if (message.to().isEmpty()) {
            return;
        }
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(message.to()).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(utf8(message.subject()))
                        .body(Body.builder().text(utf8(message.body())).build())
                        .build()).build())
                .build();
        try {
            client.sendEmail(request);
        } catch (SdkException e) {
            // never e.getMessage(): SES lists the rejected identities (e-mail addresses) in it - and the exception is
            // not passed on as a cause, so nobody logs it further up
            String detail = safeDetail(e);
            log.warn("mail.ses.failed recipients={} {}", message.to().size(), detail);
            throw new MailSendException("Falha no envio pelo SES (" + detail + ")");
        }
    }

    /** Só a classe da exceção, o código de erro da AWS, o status HTTP e o requestId - nada do texto da AWS. */
    static String safeDetail(SdkException e) {
        StringBuilder detail = new StringBuilder("error=").append(e.getClass().getSimpleName());
        if (e instanceof AwsServiceException aws) {
            if (aws.awsErrorDetails() != null && aws.awsErrorDetails().errorCode() != null) {
                detail.append(" code=").append(aws.awsErrorDetails().errorCode());
            }
            detail.append(" status=").append(aws.statusCode());
            if (aws.requestId() != null) {
                detail.append(" requestId=").append(aws.requestId());
            }
        }
        return detail.toString();
    }

    @Override
    public String from() {
        return from;
    }

    private static Content utf8(String value) {
        return Content.builder().data(value).charset(StandardCharsets.UTF_8.name()).build();
    }
}
