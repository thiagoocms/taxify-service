package br.com.cshub.taxify.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Lock nomeado do MySQL ({@code GET_LOCK}/{@code RELEASE_LOCK}) que vale até o <b>fim da transação</b> em que foi pego.
 * Serve quando não há linha para travar (o teto por IP do "Esqueci a senha", o teto global do resumo de pendências):
 * a contagem e a gravação ficam numa seção crítica entre instâncias, e o lock só é solto depois do commit - antes disso
 * a próxima contagem não enxergaria a linha nova.
 *
 * <p>O lock é da sessão do MySQL, por isso o {@code GET_LOCK} roda dentro da transação (na conexão dela) e o
 * {@code RELEASE_LOCK} roda no {@code afterCompletion} da mesma transação: nesse ponto o Spring ainda mantém a conexão
 * ligada, e a consulta participa dela sem novo commit. Sem sincronização de transação ativa (testes unitários com um
 * gerenciador falso) o {@link Held#close()} solta na hora.
 */
public final class NamedLocks {

    /** MySQL aceita nomes de até 64 caracteres. */
    static final int MAX_NAME = 64;

    private NamedLocks() {
    }

    /** {@code prefix + sha256(key)} cortado em 64 caracteres - a chave (ex.: o IP) nunca aparece no nome. */
    public static String name(String prefix, String key) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            String name = prefix + hash;
            return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Um lock pego: {@link #close()} solta na hora só quando não deu para deixar para o fim da transação. */
    public static final class Held implements AutoCloseable {
        private final Runnable release;
        private final boolean deferred;
        private boolean released;

        private Held(Runnable release, boolean deferred) {
            this.release = release;
            this.deferred = deferred;
        }

        @Override
        public void close() {
            if (!deferred && !released) {
                released = true;
                release.run();
            }
        }
    }

    /**
     * Pega o lock ({@code acquire} = {@code SELECT GET_LOCK(nome, segundos)}: 1 pegou, 0 tempo esgotado, nulo erro) e
     * agenda o {@code release} para o fim da transação atual.
     *
     * @return o lock, ou {@code null} quando não foi pego no tempo
     */
    public static Held untilTransactionEnds(Supplier<? extends Number> acquire, Runnable release) {
        Number got = acquire.get();
        if (got == null || got.intValue() != 1) {
            return null;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    release.run();
                }
            });
            return new Held(release, true);
        }
        return new Held(release, false);
    }
}
