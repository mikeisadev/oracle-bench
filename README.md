# oracle-bench

Piccola applicazione **Spring Boot 3 / Java 21** per fare benchmark di query su
**Oracle Database** e mostrare, in modo didattico, la differenza tra *bind variables*
(soft parse, cursore riusato) e concatenazione del parametro nella stringa SQL
(**hard parse** + **SQL injection**).

## Stack

- Java 21, Maven, Spring Boot 3.5.x
- Driver **ojdbc11**, connection pool **HikariCP** (max 5 connessioni)
- Frontend statico in vanilla JS (nessun framework)

## Prerequisiti

Un Oracle Database raggiungibile su `localhost:1521`, service name `FREEPDB1`,
con un utente:

| parametro     | valore        |
|---------------|---------------|
| host / porta  | localhost:1521 |
| service name  | FREEPDB1      |
| utente        | tuner         |
| password      | Corso2026#    |

La tabella `bench_customers` viene creata automaticamente all'avvio da
`src/main/resources/schema.sql`.

> **SQL_ID**: per popolare il campo `sqlId` l'utente deve poter leggere `V$SQL`.
> Come SYS/SYSTEM: `GRANT SELECT ON v_$sql TO tuner;`
> Senza questo grant l'app funziona lo stesso, ma `sqlId` resta vuoto.

## Avvio

```bash
mvn spring-boot:run
```

Poi apri **http://localhost:8080/** per la UI.

Build del jar eseguibile:

```bash
mvn clean package
java -jar target/oracle-bench.jar
```

## Configurazione — `src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
    username: tuner
    password: Corso2026#
    driver-class-name: oracle.jdbc.OracleDriver
    hikari:
      maximum-pool-size: 5
```

## REST API

### Query di benchmark

| metodo | endpoint                     | descrizione |
|--------|------------------------------|-------------|
| GET    | `/api/queries`               | elenco delle query disponibili (id + SQL) |
| GET    | `/api/query?id=<n>&p=<val>`  | esegue la query `n`; `p` è opzionale |

Risposta:

```json
{ "id": 1, "rows": 1, "elapsedMs": 0.842, "sqlId": "abc123def456" }
```

- `elapsedMs` è misurato con `System.nanoTime()` **attorno alla sola
  esecuzione + fetch** del ResultSet.
- `sqlId` è recuperato con una seconda query su `V$SQL` cercando il testo esatto
  (`WHERE sql_text = ?`); è vuoto se non trovato.

### CRUD cliente

| metodo | endpoint                          | descrizione |
|--------|-----------------------------------|-------------|
| POST   | `/api/crud/customer`              | inserisce un cliente (fittizio se il body è vuoto) |
| GET    | `/api/crud/customer?email=<e>`    | legge per email |
| PUT    | `/api/crud/customer?email=<e>`    | aggiorna il nome (body `{"name":"..."}`) |
| DELETE | `/api/crud/customer?email=<e>`    | elimina per email |

Ogni risposta include `elapsedMs`. Esempi:

```bash
# crea un cliente fittizio
curl -X POST http://localhost:8080/api/crud/customer

# crea un cliente con dati espliciti
curl -X POST http://localhost:8080/api/crud/customer \
  -H 'Content-Type: application/json' \
  -d '{"name":"Mario Rossi","email":"mario@example.com"}'

# leggi
curl 'http://localhost:8080/api/crud/customer?email=mario@example.com'

# aggiorna il nome
curl -X PUT 'http://localhost:8080/api/crud/customer?email=mario@example.com' \
  -H 'Content-Type: application/json' -d '{"name":"Mario Bianchi"}'

# elimina
curl -X DELETE 'http://localhost:8080/api/crud/customer?email=mario@example.com'
```

## Le query — `src/main/resources/queries.sql`

Formato: una riga `--#<n>` introduce la query numero `n`; le righe successive
(fino al marcatore seguente) sono il corpo SQL.

- Un `?` nel corpo è una **bind variable**: il parametro `p` della richiesta vi
  viene associato con `PreparedStatement.setString`.
- La **query 99** è l'unica eccezione: il token `__P__` viene **concatenato**
  nella stringa SQL (nessun bind). Ad ogni valore diverso corrisponde un testo
  SQL diverso → **hard parse** e nuovo `sql_id` ogni volta, oltre a essere
  vulnerabile a **SQL injection**. Serve proprio a dimostrarlo.

### Demo suggerita

1. Esegui più volte la query **#5** con lo stesso `p` (es. `Mario Rossi`):
   il `sql_id` resta costante (cursore condiviso, soft parse).
2. Esegui la query **#99** cambiando `p` ogni volta: il `sql_id` cambia
   ad ogni esecuzione (hard parse). Prova un valore come
   `x' OR '1'='1` per vedere l'iniezione.

## Log

Ogni SQL eseguita (incluse le lookup su `V$SQL`) viene loggata dal package
`com.example.oraclebench`.
