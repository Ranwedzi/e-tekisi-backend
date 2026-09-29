package com.etekisi.etekisi.agent.eta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the optional external routing provider.
 *
 * Disabled by default. When enabled, an HTTP routing API (e.g. OSRM, Mapbox,
 * Google) is consulted for ETAs; failures degrade transparently to the
 * heuristic provider. No call invents route truth.
 */
@Configuration
@ConfigurationProperties(prefix = "tekisi.agent.eta.routing")
public class RoutingProperties {
    private boolean enabled = false;
    private String baseUrl = "";
    private long timeoutMs = 1500L;
    private String apiKey = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long v) { this.timeoutMs = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
}
