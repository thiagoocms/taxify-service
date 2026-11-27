package br.com.cshub.taxify.integration.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Decorador sobre o SES ou o LOG (AV-RN-09, AV-RN-13), para todos os e-mails do Taxify:
 * <ul>
 *   <li><b>Allowlist</b> ({@code TAXIFY_MAIL_RECIPIENT_ALLOWLIST}, domínios ou endereços separados por vírgula; vazia =
 *       sem filtro): todo destinatário fora da lista é descartado antes de chamar o provedor, com o log
 *       {@code mail.recipient.skipped count={n}} sem o endereço. Sem nenhum destinatário sobrando, nada é enviado. Serve
 *       para testar com o SES ainda no sandbox.</li>
 *   <li><b>Ritmo</b> ({@code TAXIFY_MAIL_SEND_INTERVAL_MS}, padrão 1000; 0 = sem espera): entre um envio e o próximo,
 *       de qualquer thread, passa pelo menos esse intervalo - a taxa de envio do SES é da conta, não da rotina.</li>
 * </ul>
 */
public class GuardedMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(GuardedMailSender.class);

    /** Espera {@code millis} - trocável nos testes. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final MailSender delegate;
    private final List<String> allowlist;
    private final long intervalMillis;
    private final LongSupplier clockMillis;
    private final Sleeper sleeper;
    private final Object pacing = new Object();
    private long nextSlot;

    public GuardedMailSender(MailSender delegate, List<String> allowlist, long intervalMillis) {
        this(delegate, allowlist, intervalMillis, System::currentTimeMillis, Thread::sleep);
    }

    GuardedMailSender(MailSender delegate, List<String> allowlist, long intervalMillis, LongSupplier clockMillis, Sleeper sleeper) {
        this.delegate = delegate;
        this.allowlist = allowlist == null ? List.of() : allowlist.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(entry -> entry.strip().toLowerCase(Locale.ROOT))
                .map(entry -> entry.startsWith("@") ? entry.substring(1) : entry)
                .toList();
        this.intervalMillis = Math.max(0, intervalMillis);
        this.clockMillis = clockMillis;
        this.sleeper = sleeper;
    }

    @Override
    public void send(MailMessage message) {
        List<String> allowed = message.to().stream().filter(this::accepts).toList();
        int skipped = message.to().size() - allowed.size();
        if (skipped > 0) {
            log.info("mail.recipient.skipped count={}", skipped);
        }
        if (allowed.isEmpty()) {
            return;
        }
        pace();
        delegate.send(skipped == 0 ? message : new MailMessage(allowed, message.subject(), message.body()));
    }

    /** Vazia = todos; entrada com {@code @} = o endereço exato; sem {@code @} = o domínio exato (sem subdomínio). */
    @Override
    public boolean accepts(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        if (allowlist.isEmpty()) {
            return true;
        }
        String value = address.strip().toLowerCase(Locale.ROOT);
        int at = value.lastIndexOf('@');
        String domain = at < 0 ? "" : value.substring(at + 1);
        return allowlist.stream().anyMatch(entry -> entry.contains("@") ? entry.equals(value) : entry.equals(domain));
    }

    /** Reserva o próximo horário livre e espera até ele - os envios saem um por intervalo, em ordem de chegada. */
    private void pace() {
        if (intervalMillis == 0) {
            return;
        }
        long wait;
        synchronized (pacing) {
            long now = clockMillis.getAsLong();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + intervalMillis;
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MailSendException("Envio interrompido");
            }
        }
    }

    @Override
    public String from() {
        return delegate.from();
    }

    MailSender delegate() {
        return delegate;
    }
}
