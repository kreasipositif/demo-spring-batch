package com.kreasipositif.batchprocessor.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j bulkhead configuration.
 *
 * <p>Two types of bulkheads are configured:
 * <ul>
 *   <li><b>SemaphoreBulkhead</b> — limits the number of <em>concurrent</em> calls to a downstream
 *       service by blocking callers beyond {@code maxConcurrentCalls}.  Lightweight; backed by a
 *       {@link java.util.concurrent.Semaphore}.</li>
 *   <li><b>FixedThreadPoolBulkhead</b> — executes calls on a dedicated, bounded thread pool and
 *       queues excess work up to {@code queueCapacity}.  Suitable for fire-and-forget /
 *       CompletableFuture usage.</li>
 * </ul>
 *
 * <p>Property values are read from {@code application.yml} under the {@code resilience4j.*}
 * namespace so that they can be overridden per environment without recompilation.
 */
@Slf4j
@Configuration
public class Resilience4jConfig {

    // ── SemaphoreBulkhead — config-service ───────────────────────────────────

    @Value("${resilience4j.bulkhead.instances.configServiceBulkhead.max-concurrent-calls:20}")
    private int configServiceMaxConcurrent;

    @Value("${resilience4j.bulkhead.instances.configServiceBulkhead.max-wait-duration:500ms}")
    private Duration configServiceMaxWait;

    // ── SemaphoreBulkhead — account-validation-service ───────────────────────

    @Value("${resilience4j.bulkhead.instances.accountValidationBulkhead.max-concurrent-calls:20}")
    private int accountValidationMaxConcurrent;

    @Value("${resilience4j.bulkhead.instances.accountValidationBulkhead.max-wait-duration:500ms}")
    private Duration accountValidationMaxWait;

    // ── FixedThreadPoolBulkhead — account-validation-service ─────────────────

    @Value("${resilience4j.thread-pool-bulkhead.instances.accountValidationThreadPoolBulkhead.max-thread-pool-size:20}")
    private int tpMaxPoolSize;

    @Value("${resilience4j.thread-pool-bulkhead.instances.accountValidationThreadPoolBulkhead.core-thread-pool-size:10}")
    private int tpCorePoolSize;

    @Value("${resilience4j.thread-pool-bulkhead.instances.accountValidationThreadPoolBulkhead.queue-capacity:200}")
    private int tpQueueCapacity;

    @Value("${resilience4j.thread-pool-bulkhead.instances.accountValidationThreadPoolBulkhead.keep-alive-duration:20ms}")
    private Duration tpKeepAlive;

    // ─── Beans ───────────────────────────────────────────────────────────────

    /**
     * SemaphoreBulkhead for calls to <em>config-service</em> (bank-code + amount validation).
     */
    @Bean("configServiceBulkhead")
    public Bulkhead configServiceBulkhead(BulkheadRegistry registry) {
        BulkheadConfig cfg = BulkheadConfig.custom()
                .maxConcurrentCalls(configServiceMaxConcurrent)
                .maxWaitDuration(configServiceMaxWait)
                .build();
        Bulkhead bh = registry.bulkhead("configServiceBulkhead", cfg);
        log.info("SemaphoreBulkhead 'configServiceBulkhead' created — maxConcurrent={}, maxWait={}",
                configServiceMaxConcurrent, configServiceMaxWait);
        return bh;
    }

    /**
     * SemaphoreBulkhead for calls to <em>account-validation-service</em> (synchronous path).
     */
    @Bean("accountValidationBulkhead")
    public Bulkhead accountValidationBulkhead(BulkheadRegistry registry) {
        BulkheadConfig cfg = BulkheadConfig.custom()
                .maxConcurrentCalls(accountValidationMaxConcurrent)
                .maxWaitDuration(accountValidationMaxWait)
                .build();
        Bulkhead bh = registry.bulkhead("accountValidationBulkhead", cfg);
        log.info("SemaphoreBulkhead 'accountValidationBulkhead' created — maxConcurrent={}, maxWait={}",
                accountValidationMaxConcurrent, accountValidationMaxWait);
        return bh;
    }

    /**
     * FixedThreadPoolBulkhead for async calls to <em>account-validation-service</em>.
     *
     * <p>Used when account validation is dispatched as a {@link java.util.concurrent.CompletableFuture}
     * from within a virtual-thread worker so that the thread-pool bulkhead controls
     * back-pressure on the blocking downstream latency.
     */
    @Bean("accountValidationThreadPoolBulkhead")
    public ThreadPoolBulkhead accountValidationThreadPoolBulkhead(ThreadPoolBulkheadRegistry registry) {
        ThreadPoolBulkheadConfig cfg = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(tpMaxPoolSize)
                .coreThreadPoolSize(tpCorePoolSize)
                .queueCapacity(tpQueueCapacity)
                .keepAliveDuration(tpKeepAlive)
                .build();
        ThreadPoolBulkhead bh = registry.bulkhead("accountValidationThreadPoolBulkhead", cfg);
        log.info("ThreadPoolBulkhead 'accountValidationThreadPoolBulkhead' created — "
                        + "corePool={}, maxPool={}, queue={}",
                tpCorePoolSize, tpMaxPoolSize, tpQueueCapacity);
        return bh;
    }

    // ─── Registries (default instances — auto-configured by resilience4j-spring-boot3) ─

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    @Bean
    public ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry() {
        return ThreadPoolBulkheadRegistry.ofDefaults();
    }
}
