package io.github.doctor277.probeworker;

import java.net.http.HttpClient;
import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class ProbeConfiguration {
    @Bean
    Clock workerClock() { return Clock.systemUTC(); }

    @Bean
    HttpClient probeHttpClient(WorkerProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Bean
    ThreadPoolTaskExecutor probeExecutor(WorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("health-probe-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(75);
        return executor;
    }

    @Bean
    InitializingBean workerWorkloadMetrics(MeterRegistry registry, ThreadPoolTaskExecutor executor) {
        return () -> {
            registry.gauge("launchguard.probe.worker.active", executor, ThreadPoolTaskExecutor::getActiveCount);
            registry.gauge("launchguard.probe.worker.queued", executor,
                    pool -> pool.getThreadPoolExecutor().getQueue().size());
        };
    }
}
