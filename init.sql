-- Runs once when the dev Postgres container is first created.
-- Tables are created by Flyway migrations (e-tekisi/src/main/resources/db/migration)
-- when the backend starts, so only the schemas are made here.
CREATE SCHEMA IF NOT EXISTS commuter_schema;
CREATE SCHEMA IF NOT EXISTS driver_schema;
CREATE SCHEMA IF NOT EXISTS agent_schema;
