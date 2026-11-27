package br.com.cshub.taxify.integration.mail;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** AV-RN-09 (CT-AV-18) and AV-RN-13 (CA-AV-13, CT-AV-27): pace between sends and the recipient allowlist. */
class GuardedMailSenderTest {

    private final List<MailMessage> delivered = new ArrayList<>();
    private final MailSender provider = new MailSender() {
        @Override
        public void send(MailMessage message) {
            delivered.add(message);
        }

        @Override
        public String from() {
            return "nao-responda@cshub.com.br";
        }
    };

    private static MailMessage to(String... addresses) {
        return new MailMessage(List.of(addresses), "Assunto", "Corpo");
    }

    @Test
    void anEmptyAllowlistLetsEverythingThrough() {
        GuardedMailSender sender = new GuardedMailSender(provider, List.of(), 0);
        sender.send(to("ana@exemplo.test"));
        assertThat(delivered).hasSize(1);
        assertThat(sender.accepts("qualquer@outro.test")).isTrue();
        assertThat(sender.from()).isEqualTo("nao-responda@cshub.com.br");
    }

    /** CA-AV-13: TAXIFY_MAIL_RECIPIENT_ALLOWLIST=cshub.com.br - only @cshub.com.br recipients. */
    @Test
    void onlyAllowedDomainsAndAddressesGetTheMail() {
        GuardedMailSender sender = new GuardedMailSender(provider, List.of(" CSHUB.com.br ", "teste@exemplo.test", ""), 0);

        sender.send(to("carla@cshub.com.br", "ana@exemplo.test", "teste@exemplo.test"));
        sender.send(to("ana@exemplo.test"));

        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().to()).containsExactly("carla@cshub.com.br", "teste@exemplo.test");
        assertThat(sender.accepts("Carla@CsHub.com.br")).isTrue();
        // the domain is exact: neither subdomains nor look-alikes
        assertThat(sender.accepts("x@mail.cshub.com.br")).isFalse();
        assertThat(sender.accepts("x@cshub.com.br.evil.test")).isFalse();
        assertThat(sender.accepts(null)).isFalse();
    }

    /** CT-AV-18: 5 sends with 1000 ms - at least 4 s between the first and the last. */
    @Test
    void sendsArePacedByTheInterval() {
        AtomicLong now = new AtomicLong(10_000);
        List<Long> sleeps = new ArrayList<>();
        GuardedMailSender sender = new GuardedMailSender(provider, List.of(), 1000, now::get, millis -> {
            sleeps.add(millis);
            now.addAndGet(millis);
        });
        long start = now.get();
        for (int i = 0; i < 5; i++) {
            sender.send(to("ana" + i + "@exemplo.test"));
        }
        assertThat(delivered).hasSize(5);
        assertThat(now.get() - start).isGreaterThanOrEqualTo(4000);
        assertThat(sleeps).containsExactly(1000L, 1000L, 1000L, 1000L);
    }

    /** Time already passed since the last send counts: no extra wait. */
    @Test
    void noWaitWhenTheIntervalHasAlreadyPassed() {
        AtomicLong now = new AtomicLong(10_000);
        List<Long> sleeps = new ArrayList<>();
        GuardedMailSender sender = new GuardedMailSender(provider, List.of(), 1000, now::get, sleeps::add);
        sender.send(to("a@exemplo.test"));
        now.addAndGet(5000);
        sender.send(to("b@exemplo.test"));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aDroppedMailDoesNotTakeASlot() {
        AtomicLong now = new AtomicLong(10_000);
        List<Long> sleeps = new ArrayList<>();
        GuardedMailSender sender = new GuardedMailSender(provider, List.of("cshub.com.br"), 1000, now::get, sleeps::add);
        sender.send(to("fora@exemplo.test"));
        sender.send(to("dentro@cshub.com.br"));
        assertThat(sleeps).isEmpty();
        assertThat(delivered).hasSize(1);
    }
}
