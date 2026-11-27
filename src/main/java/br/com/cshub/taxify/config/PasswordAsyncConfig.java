package br.com.cshub.taxify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * O trabalho de senha que roda depois da resposta (spec 2026-11-27): o processamento do "Esqueci a senha" (SN-RN-01) e
 * o aviso de senha alterada (SN-RN-12). Limitado: no máximo {@code TAXIFY_PASSWORD_ASYNC_MAX_CONCURRENT} ao mesmo tempo
 * (threads virtuais) e {@code TAXIFY_PASSWORD_ASYNC_QUEUE_SIZE} na fila; acima disso o executor recusa
 * ({@code RejectedExecutionException}) e quem chama descarta com log só de contagem - um pico de pedidos não abre
 * threads nem conexões sem fim.
 */
@Configuration
public class PasswordAsyncConfig {

    public static final String EXECUTOR = "passwordMailExecutor";

    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService passwordMailExecutor(PasswordProperties properties) {
        int threads = Math.max(1, properties.getAsyncMaxConcurrent());
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getAsyncQueueSize())),
                Thread.ofVirtual().name("password-mail-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
