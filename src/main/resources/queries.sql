-- Predefined benchmark queries.
-- Format: a line "--#<n>" introduces query number <n>; every following line
-- until the next "--#" (or EOF) is the SQL body for that query.
--
-- Conventions used by the app:
--   * A "?" in the body is a JDBC bind variable; the request param "p" is bound to it.
--   * Query 99 is the ONLY exception: the token __P__ is string-concatenated into
--     the SQL (no bind), on purpose, to demonstrate hard parsing and SQL injection.

--#1
SELECT sysdate AS now FROM dual

--#2
SELECT count(*) AS object_count FROM all_objects

--#3
SELECT object_name, object_type
FROM all_objects
WHERE rownum <= 50
ORDER BY object_name

--#4
SELECT id, name, email, created_at FROM bench_customers ORDER BY id

--#5
SELECT id, name, email, created_at FROM bench_customers WHERE name = ?

--#6
SELECT id, name, email, created_at FROM bench_customers WHERE email = ?

--#99
SELECT id, name, email, created_at FROM bench_customers WHERE name = '__P__'
