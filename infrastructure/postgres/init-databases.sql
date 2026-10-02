-- Runs only when the PostgreSQL data volume is first initialized.
-- Service-owned business tables and Flyway migrations belong in services/.
CREATE DATABASE auth_db;
CREATE DATABASE quote_db;
CREATE DATABASE pricing_db;
CREATE DATABASE notification_db;
CREATE DATABASE audit_db;
