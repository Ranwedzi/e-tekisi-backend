package com.etekisi.etekisi.agent;

import com.etekisi.etekisi.config.SloMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.MDC;
import com.etekisi.etekisi.agent.observability.CorrelationContext;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executor for the agent layer. The MDC + CorrelationContext are
 * propagated into the worker thread so safety-monitor listeners log under
 * the same correlation id as the originating HTTP request.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AgentAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentAsyncConfig.class);

    /**
     * Bounded async executor for agent listeners.
     *
     * <p>Phase 2.4 hardening:
     * <ul>
     *   <li>Queue capped at 64 (down from 256). Prefer to drop loudly than to
     *       grow an unbounded backlog under burst — backpressure is a feature.</li>
     *   <li>{@link RejectedExecutionHandler} emits a {@code tekisi.events.dropped}
     *       counter and a WARN log instead of OOMing or throwing into the
     *       publisher.</li>
     *   <li>{@code waitForTasksToCompleteOnShutdown=true} so an orderly shutdown
     *       drains in-flight safety evaluations instead of stranding pings.</li>
     * </ul>
     */
    @Bean(name = "agentTaskExecutor")
    public Executor agentTaskExecutor(ObjectProvider<SloMetrics> sloProvider) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("agent-");
        exec.setTaskDecorator(correlationPropagatingDecorator());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(15);
        exec.setRejectedExecutionHandler(droppingHandler(sloProvider));
        exec.initialize();
        return exec;
    }

    private RejectedExecutionHandler droppingHandler(ObjectProvider<SloMetrics> sloProvider) {
        return (Runnable r, ThreadPoolExecutor executor) -> {
            log.warn("agent.async.dropped active={} queueRemainingCapacity={} — backpressure dropping task",
                    executor.getActiveCount(), executor.getQueue().remainingCapacity());
            SloMetrics m = sloProvider.getIfAvailable();
            if (m != null) m.eventDropped("agentTaskExecutor");
            // Intentionally do NOT throw. The event publisher must keep flowing.
        };
    }

    private TaskDecorator correlationPropagatingDecorator() {
        return runnable -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            String correlationId = CorrelationContext.get();
            return () -> {
                try {
                    if (mdc != null) MDC.setContextMap(mdc);
                    if (correlationId != null) CorrelationContext.set(correlationId);
                    runnable.run();
                } finally {
                    MDC.clear();
                    CorrelationContext.clear();
                }
            };
        };
    }
}
