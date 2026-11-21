package br.com.cshub.taxify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool for integration jobs (billing sync, Acessórias import) started by a button or the
 * daily schedulers. Small on purpose: each job already fans out to the provider's API, and
 * these APIs rate-limit - a few firms at a time is plenty.
 */
@Configuration
public class IntegrationJobConfig {

    public static final String EXECUTOR = "integrationJobExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor integrationJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("integration-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
