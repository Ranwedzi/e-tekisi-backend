package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.cluster.RedisLiveEventRelay;
import com.etekisi.etekisi.cluster.RedisSharedState;
import com.etekisi.etekisi.cluster.SharedState;
import com.etekisi.etekisi.realtime.LiveEventRelay;
import com.etekisi.etekisi.security.LoginThrottle;
import com.etekisi.etekisi.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two backend "instances" sharing one Redis: the app's own beans are
 * instance A, and a second set built on the same Redis is instance B.
 * Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "tekisi.redis.enabled=true")
class RedisClusterTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private SharedState stateA;
    @Autowired private LoginThrottle throttleA;
    @Autowired private LiveEventRelay relayA;
    @Autowired private StringRedisTemplate template;
    @Autowired private RedisConnectionFactory factory;

    @Test
    void theAppUsesRedisWhenEnabled() {
        assertThat(stateA).isInstanceOf(RedisSharedState.class);
        assertThat(stateA.shared()).isTrue();
        assertThat(relayA).isInstanceOf(RedisLiveEventRelay.class);
    }

    @Test
    void countersAndValuesAreSharedAndExpire() throws Exception {
        SharedState stateB = new RedisSharedState(template);
        String k = "test:" + System.nanoTime();
        assertThat(stateA.increment(k, Duration.ofSeconds(30))).isEqualTo(1);
        assertThat(stateB.increment(k, Duration.ofSeconds(30))).isEqualTo(2);
        assertThat(stateB.ttl(k)).isBetween(Duration.ofSeconds(25), Duration.ofSeconds(30));

        assertThat(stateA.putIfAbsent(k + ":slot", "1", Duration.ofMillis(300))).isTrue();
        assertThat(stateB.putIfAbsent(k + ":slot", "1", Duration.ofMillis(300))).isFalse(); // one slot, one taker
        Thread.sleep(400);
        assertThat(stateB.get(k + ":slot")).isEmpty();
        assertThat(stateB.putIfAbsent(k + ":slot", "1", Duration.ofMillis(300))).isTrue();
    }

    @Test
    void wrongPasswordsOnOneInstanceLockTheAccountOnAnother() {
        LoginThrottle throttleB = new LoginThrottle(new RedisSharedState(template), 5, 15, 1000);
        String account = LoginThrottle.accountKey("COMMUTER", "victim" + System.nanoTime() + "@test.dev");
        for (int i = 0; i < 5; i++) {
            (i % 2 == 0 ? throttleA : throttleB).recordFailure(account); // the attacker is spread over both
        }
        assertThatThrownBy(() -> throttleB.checkAccount(account))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many attempts");
        assertThatThrownBy(() -> throttleA.checkAccount(account)).isInstanceOf(ApiException.class);
        throttleA.recordSuccess(account);
        throttleB.checkAccount(account); // cleared everywhere
    }

    @Test
    void liveEventsPublishedOnOneInstanceReachTheOther() throws Exception {
        RedisLiveEventRelay relayB = new RedisLiveEventRelay(template, factory);
        List<String> heardOnB = new CopyOnWriteArrayList<>();
        relayB.onMessage((key, topic) -> heardOnB.add(key + "|" + topic));
        try {
            Thread.sleep(300); // subscription is up
            relayA.publish("COMMUTER:42", "bookings");
            for (int i = 0; i < 40 && heardOnB.isEmpty(); i++) Thread.sleep(50);
            assertThat(heardOnB).contains("COMMUTER:42|bookings");
        } finally {
            relayB.stop();
        }
    }
}
