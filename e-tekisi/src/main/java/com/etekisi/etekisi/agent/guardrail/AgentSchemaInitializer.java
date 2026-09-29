package com.etekisi.etekisi.agent.guardrail;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate's ddl-auto=update creates tables but does NOT create schemas.
 * The audit entity lives in agent_schema, so we ensure the schema exists
 * before Hibernate tries to create the table inside it.
 */
@Component
public class AgentSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentSchemaInitializer.class);
    private final JdbcTemplate jdbc;

    public AgentSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS agent_schema");
        } catch (Exception e) {
            log.warn("agent.schema.init.skipped reason={}", e.getMessage());
        }
    }
}
