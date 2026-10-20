package com.etekisi.etekisi.realtime;

import java.util.function.BiConsumer;

/**
 * Carries a live event to every backend instance that may hold the
 * recipient's stream. {@link #local()} (the default) is for a single instance;
 * with several, a shared-bus implementation (Redis pub/sub) is used.
 */
public interface LiveEventRelay {

    /** Sends (recipient key, topic) to all instances, including this one. */
    void publish(String key, String topic);

    /** Registers this instance's local delivery. Called once at start-up. */
    void onMessage(BiConsumer<String, String> deliver);

    /** A new in-process relay (one per LiveEvents; never shared, or one app could deliver another's events). */
    static LiveEventRelay local() {
        return new LiveEventRelay() {
            private volatile BiConsumer<String, String> deliver = (k, t) -> {};

            @Override
            public void publish(String key, String topic) {
                deliver.accept(key, topic);
            }

            @Override
            public void onMessage(BiConsumer<String, String> deliver) {
                this.deliver = deliver;
            }
        };
    }
}
