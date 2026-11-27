package br.com.cshub.taxify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * O relógio das regras da assinatura do escritório (teste grátis, bloqueio, avisos, verificação de e-mail): sempre
 * America/Recife, nunca o fuso da JVM - no container ela roda em UTC (F1-RN-05). Os testes trocam por um Clock fixo.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId RECIFE = ZoneId.of("America/Recife");

    @Bean
    public Clock recifeClock() {
        return Clock.system(RECIFE);
    }
}
