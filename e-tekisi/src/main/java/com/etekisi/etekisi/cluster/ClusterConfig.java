package com.etekisi.etekisi.cluster;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * What changes when e-Tekisi runs on more than one backend instance.
 *
 * <ul>
 *   <li><b>Scheduled jobs</b> that change data (purges, retention, offer
 *       expiry, reconciliation, document expiry) take a lock in Postgres
 *       ({@code agent_schema.shedlock}) and so run on one instance at a time.
 *       On by default; it costs one row update per job run.</li>
 *   <li><b>Shared state and live events</b> use Redis when
 *       {@code tekisi.redis.enabled=true} (REDIS_ENABLED); otherwise memory,
 *       which is correct only for a single instance.</li>
 * </ul>
 */
@Configuration
public class ClusterConfig {

    private static final Logger log = LoggerFactory.getLogger(ClusterConfig.class);

    @Configuration
    @ConditionalOnProperty(name = "tekisi.scheduling.locks", havingValue = "true", matchIfMissing = true)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
    static class SchedulerLocks {
        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .withTableName("agent_schema.shedlock")
                    .usingDbTime()
                    .build());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "tekisi.redis.enabled", havingValue = "true")
    static class Redis {
        @Bean
        SharedState sharedState(StringRedisTemplate redis) {
            log.info("cluster.shared_state=redis");
            return new RedisSharedState(redis);
        }

        @Bean(destroyMethod = "stop")
        RedisLiveEventRelay liveEventRelay(StringRedisTemplate redis, RedisConnectionFactory factory) {
            return new RedisLiveEventRelay(redis, factory);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "tekisi.redis.enabled", havingValue = "false", matchIfMissing = true)
    static class Memory {
        @Bean
        SharedState sharedState(@Value("${spring.profiles.active:}") String profiles) {
            if (profiles.contains("prod")) {
                log.warn("cluster.shared_state=memory: correct for ONE backend instance only. "
                        + "Set REDIS_ENABLED=true (and REDIS_HOST) before running more than one.");
            }
            return new MemorySharedState();
        }
    }
}
