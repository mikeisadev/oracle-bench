-- Created at startup by Spring Boot's DataSource initializer.
-- continue-on-error=true in application.yml means the CREATE is skipped
-- (ORA-00955: name already used) on subsequent restarts.
CREATE TABLE bench_customers (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR2(100),
    email      VARCHAR2(100),
    created_at TIMESTAMP DEFAULT systimestamp
);
