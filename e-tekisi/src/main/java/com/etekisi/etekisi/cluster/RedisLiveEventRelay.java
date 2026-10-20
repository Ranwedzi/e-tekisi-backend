package com.etekisi.etekisi.cluster;

import com.etekisi.etekisi.realtime.LiveEventRelay;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Live events over Redis pub/sub: every instance publishes to one channel
 * and delivers what it hears to the streams it holds, so a commuter on
 * instance A hears about a driver's offer made through instance B.
 */
public class RedisLiveEventRelay implements LiveEventRelay {

    static final String CHANNEL = "tekisi:live";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private volatile BiConsumer<String, String> deliver = (k, t) -> {};

    public RedisLiveEventRelay(StringRedisTemplate redis, RedisConnectionFactory factory) {
        this.redis = redis;
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            int i = body.indexOf('|');
            if (i > 0) deliver.accept(body.substring(0, i), body.substring(i + 1));
        }, new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        container.start();
    }

    @Override
    public void publish(String key, String topic) {
        redis.convertAndSend(CHANNEL, key + "|" + topic);
    }

    @Override
    public void onMessage(BiConsumer<String, String> deliver) {
        this.deliver = deliver;
    }

    public void stop() {
        container.stop();
    }
}
