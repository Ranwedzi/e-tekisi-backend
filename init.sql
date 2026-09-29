-- Create Schemas
CREATE SCHEMA IF NOT EXISTS commuter_schema;
CREATE SCHEMA IF NOT EXISTS driver_schema;
-- Schema for the AI-agent layer (decision_audit table is created by Hibernate)
CREATE SCHEMA IF NOT EXISTS agent_schema;

-- Commuter Table
CREATE TABLE commuter_schema.users (
    id SERIAL PRIMARY KEY,
    full_name VARCHAR(100),
    email VARCHAR(100) UNIQUE,
    phone_number VARCHAR(15),
    password VARCHAR(255)
);

-- Driver Table
CREATE TABLE driver_schema.drivers (
    id SERIAL PRIMARY KEY,
    full_name VARCHAR(100),
    taxi_plate VARCHAR(15),
    license_number VARCHAR(50),
    is_available BOOLEAN DEFAULT false
);