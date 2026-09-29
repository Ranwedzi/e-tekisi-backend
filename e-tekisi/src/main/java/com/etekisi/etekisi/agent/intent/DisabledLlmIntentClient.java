package com.etekisi.etekisi.agent.intent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

/**
 * Default no-op LLM client. Wins by @ConditionalOnMissingBean so any real
 * implementation supplied by the application replaces it transparently.
 */
@Configuration
public class DisabledLlmIntentClient {

    @Bean
    @ConditionalOnMissingBean(LlmIntentClient.class)
    public LlmIntentClient noOpLlmIntentClient() {
        return new LlmIntentClient() {
            @Override public Optional<RawIntent> extract(String userText) { return Optional.empty(); }
        };
    }
}
