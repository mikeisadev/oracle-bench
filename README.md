# oracle-bench

Piccola applicazione **Spring Boot 3 / Java 21** per fare benchmark di query su
**Oracle Database** e mostrare, in modo didattico, la differenza tra *bind variables*
(soft parse, cursore riusato) e concatenazione del parametro nella stringa SQL
(**hard parse** + **SQL injection**).

Oltre a essere uno strumento, questo README è pensato per **insegnare**: spiega
riga per riga la configurazione, le dipendenze, la stringa di connessione e il
connection pool, così da poterli governare con consapevolezza.

## Indice

1. [Stack tecnologico e perché](#stack-tecnologico-e-perché)
2. [Prerequisiti — Oracle Database](#prerequisiti--oracle-database)
3. [Avvio](#avvio)
4. [Le dipendenze — `pom.xml`](#le-dipendenze--pomxml)
5. [La configurazione — `application.yml` riga per riga](#la-configurazione--applicationyml-riga-per-riga)
6. [La stringa di connessione, in dettaglio](#la-stringa-di-connessione-in-dettaglio)
7. [HikariCP: cos'è, perché, come è configurato](#hikaricp-cosè-perché-come-è-configurato)
8. [Governare la configurazione (profili, segreti, tuning)](#governare-la-configurazione-profili-segreti-tuning)
9. [REST API](#rest-api)
10. [Le query — `queries.sql`](#le-query--queriessql)
11. [Log](#log)

---

## Stack tecnologico e perché

| Componente | Ruolo | Perché è qui |
|------------|-------|--------------|
| **Java 21** | linguaggio/runtime | LTS moderno; usa `record` e testo multilinea |
| **Maven** | build & dipendenze | standard, con wrapper incluso (`./mvnw`) |
| **Spring Boot 3.5** | framework web + auto-config | avvia un server HTTP, configura da solo il DataSource a partire da `application.yml` |
| **spring-boot-starter-web** | REST + Tomcat embedded | espone le API e serve `index.html` |
| **spring-boot-starter-jdbc** | JDBC + **HikariCP** | dà `DataSource`/`JdbcTemplate` e il connection pool |
| **ojdbc11** | driver JDBC Oracle | traduce le chiamate JDBC nel protocollo di rete di Oracle |
| **Oracle Database** | il database | è il soggetto del benchmark (parsing, `V$SQL`, `sql_id`) |
| **HikariCP** | connection pool | riusa le connessioni invece di aprirle a ogni richiesta |

Nessun framework JavaScript lato client: la UI è HTML + vanilla JS, per tenere il
focus sul database.

> **Ambiente testato (E2E):** immagine community `gvenzl/oracle-free:slim-faststart`,
> che oggi porta **Oracle AI Database 26ai Free 23.26.2.0.0**. Questa immagine include
> **SQL\*Plus** ma **non SQLcl** (`sql`): per i comandi da terminale del corso usa
> `sqlplus`. Tutta la SQL usata dall'app (`EXPLAIN PLAN`, `DBMS_XPLAN.DISPLAY`/
> `DISPLAY_CURSOR`, `V$SQL`, `V$MYSTAT`, `DBMS_STATS`, `LISTAGG`, `GENERATED AS
> IDENTITY`, `FETCH FIRST`, `ALTER SESSION SET STATISTICS_LEVEL`) esiste identica anche
> su **Oracle 19c** ufficiale (`container-registry.oracle.com/.../enterprise:19.3.0.0`),
> quindi gli esempi sono compatibili con entrambe le versioni.

---

## Prerequisiti — Oracle Database

Serve un Oracle Database in ascolto su `localhost:1521`, **service name**
`FREEPDB1`, con un utente applicativo:

| parametro     | valore          |
|---------------|-----------------|
| host / porta  | localhost:1521  |
| service name  | FREEPDB1        |
| utente        | corso           |
| password      | Corso2026       |

> ⚠️ **Password reale del container**: `Corso2026` (senza `#`). L'abstract cita
> `Corso2026#`, ma il container `gvenzl` in uso è stato avviato con `Corso2026` per
> `system` e `corso` (verificato via `sqlplus` dentro il container). `application.yml`
> è quindi corretto. Se le cambi, aggiorna anche questa tabella (o usa variabili
> d'ambiente, vedi [Governare la configurazione](#governare-la-configurazione-profili-segreti-tuning)).

### Cos'è `FREEPDB1` (e perché "service name" e non "SID")

**Oracle Database Free** (l'edizione gratuita, ex *XE*) si installa con
un'architettura *multitenant*:

- un **CDB** (Container Database) chiamato `FREE`, che contiene le strutture di sistema;
- una **PDB** (Pluggable Database) applicativa chiamata **`FREEPDB1`**, dove vivono
  i tuoi schemi e le tue tabelle.

Ti connetti alla **PDB**, e lo fai tramite il suo **service name** (`FREEPDB1`), non
tramite il vecchio **SID**. Differenza concettuale:

- **SID** = il nome di *una singola istanza* Oracle (concetto storico, pre-multitenant).
- **Service name** = il nome di *un servizio* che il listener espone; in ambiente
  multitenant ogni PDB è un servizio. È l'approccio moderno e obbligatorio per le PDB.

Il **listener** Oracle (processo in ascolto sulla 1521) fa da centralino: riceve la
richiesta, legge il service name e la instrada alla PDB giusta.

### Creare l'utente (una tantum, come SYS)

Il corso (vedi l'abstract §6.5) usa un utente **`tuner`** con accesso in lettura agli
schemi demo HR/SH e al dizionario dati:

```sql
-- collegati alla PDB, non al CDB
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER tuner IDENTIFIED BY "Corso2026#" QUOTA UNLIMITED ON users;
-- NB: non esiste il privilegio "CREATE INDEX": per indicizzare una PROPRIA tabella
-- basta possederla (CREATE TABLE). CREATE ANY INDEX serve solo per tabelle altrui.
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SYNONYM TO tuner;

-- viste V$ e dizionario (sql_id, autotrace, V$SQL, piani runtime, checklist dataset)
GRANT SELECT ANY DICTIONARY TO tuner;      -- comodità di laboratorio

-- lettura degli schemi demo (per la scheda Tabelle/Dataset e le Query custom)
GRANT SELECT ON sh.sales     TO tuner;
GRANT SELECT ON sh.customers TO tuner;
GRANT SELECT ON sh.products  TO tuner;
GRANT SELECT ON sh.times     TO tuner;
GRANT SELECT ON hr.employees TO tuner;
GRANT SELECT ON hr.departments TO tuner;
-- (per i moduli 3–12) tracing e SQL Plan Baselines:
GRANT EXECUTE ON DBMS_MONITOR TO tuner;
GRANT ADMINISTER SQL MANAGEMENT OBJECT TO tuner;
```

> ⚠️ **Utente in `application.yml`**: il file punta a `corso`/`Corso2026` (l'`APP_USER`
> del container `gvenzl`). Appena creato, `corso` ha solo `DB_DEVELOPER_ROLE` e **non
> vede né `V$SQL` né HR/SH** (ORA-00942): Autotrace, V$SQL stats e la checklist HR
> restano rosse. Nel test E2E ho concesso a `corso` i grant minimi (come `system`,
> dentro il container) — replicali se usi `corso`:
>
> ```sql
> -- come system @ FREEPDB1
> GRANT SELECT ANY DICTIONARY TO corso;        -- V$SQL, V$MYSTAT, piani runtime
> BEGIN
>   FOR t IN (SELECT table_name FROM dba_tables WHERE owner = 'HR') LOOP
>     EXECUTE IMMEDIATE 'GRANT SELECT ON HR.' || t.table_name || ' TO corso';
>   END LOOP;
> END;
> /
> ```
>
> In alternativa, per seguire l'abstract alla lettera, connetti l'app come **`tuner`**.

> **Cosa serve a cosa**: `SELECT ANY DICTIONARY` (o `SELECT_CATALOG_ROLE`) abilita le
> letture su `V$SQL`, `V$SQL_PLAN`, `V$SQL_PLAN_STATISTICS_ALL`, `V$SESSION`,
> `V$MYSTAT`, `V$STATNAME` usate da Autotrace e dal pannello V$SQL. Senza, il
> Benchmark funziona comunque (con `sqlId` vuoto) ed `EXPLAIN PLAN` pure (usa solo il
> PLAN_TABLE); Autotrace e le statistiche V$SQL no.

> ⚠️ **Licenze**: gli strumenti qui usano solo `EXPLAIN PLAN`, `DBMS_XPLAN` e le viste
> `V$`, che **non** richiedono il Diagnostic/Tuning Pack. Su Oracle Free/Standard
> Edition evita invece AWR, ASH, SQL Monitor e SQL Tuning Advisor: sono a pagamento e
> licenziati solo su Enterprise Edition con quei pack.

La tabella `bench_customers` **non** la crei a mano: viene creata all'avvio da
`src/main/resources/schema.sql` (vedi `spring.sql.init` più avanti).

### Installare gli schemi demo HR e SH

I sample schemas sono già scaricati nel container `gvenzl` sotto
`/opt/oracle/db-sample-schemas-23.3/`. **HR** si installa con `sqlplus`. **SH** invece
carica i CSV grandi (sales.csv ≈ 918k righe) con il comando **`LOAD` di SQLcl**
(`sh_populate.sql`), e l'immagine `slim` **non include SQLcl**. Due strade:

- **Immagine 19c ufficiale** (o qualunque ambiente con SQLcl): esegui gli script così
  come sono — `sql system/…@//host:1521/SERVICE @sh_install.sql`.
- **Immagine community `slim` (senza SQLcl):** scarica SQLcl a parte (gira con un
  qualsiasi JDK 17+) e lancia lo stesso `sh_install.sql` puntando a `localhost:1521`;
  i CSV si trovano nel container (copiabili con `docker cp`). È così che questo SH è
  stato installato e verificato: SALES 918.843, CUSTOMERS 55.500, COSTS 82.112, ecc.

> ⚠️ **Due trappole dell'immagine `slim`/dataset recente, verificate:**
> 1. L'ultimo passo di `sh_populate.sql` crea un indice **Oracle Text**
>    (`INDEXTYPE IS ctxsys.context` su `supplementary_demographics`): fallisce con
>    **ORA-29833** perché la `slim` rimuove Oracle Text. È un indice **non essenziale**
>    per il corso — tutti i dati SH sono comunque caricati.
> 2. In `db-sample-schemas-23.3` le date di SH vanno da **2019-01-01 a 2023-12-31**,
>    non più dal 1998–2001 dello storico SH. **Adatta le date degli esempi** (l'abstract
>    cita `DATE '2001-06-15'`, che qui restituirebbe 0 righe).

Dopo l'install, dai all'utente dell'app la lettura su SH (già fatto nel test E2E):

```sql
BEGIN
  FOR t IN (SELECT table_name FROM dba_tables WHERE owner = 'SH') LOOP
    EXECUTE IMMEDIATE 'GRANT SELECT ON SH.' || t.table_name || ' TO corso';
  END LOOP;
END;
/
EXEC DBMS_STATS.GATHER_SCHEMA_STATS('SH');
```

---

## Avvio

Il progetto include il **Maven Wrapper**, quindi non serve avere Maven installato:
`mvnw` scarica automaticamente la versione corretta al primo utilizzo.

**macOS / Linux:**

```bash
./mvnw spring-boot:run
```

**Windows (cmd / PowerShell):**

```bat
mvnw.cmd spring-boot:run
```

Poi apri **http://localhost:8080/** per la UI.

Build del jar eseguibile:

```bash
./mvnw clean package        # Windows: mvnw.cmd clean package
java -jar target/oracle-bench.jar
```

> In alternativa, se hai già Maven installato, funzionano anche `mvn spring-boot:run`
> / `mvn clean package`.

---

## Setup automatico (pulsante «Avvia setup»)

In alto a destra il pulsante **▶ Avvia setup** configura il database per il corso in
un colpo solo. Apre un dialog che spiega cosa accadrà; se annulli non succede nulla, se
confermi parte la procedura e vedrai **ogni comando in tempo reale** in un terminale
(xterm.js) alimentato via Server-Sent Events. Il terminale occupa tutta la larghezza e
ha un pulsante **Schermo intero** (accanto a *Chiudi*) per espanderlo e ridurlo.

**Pre-flight**: alla conferma l'app verifica che il **container Docker sia attivo**
(`GET /api/setup/status` → `docker inspect`). Se Docker non è raggiungibile o il
container non è in esecuzione, compare un **dialog d'errore** con le istruzioni per
avviarlo (le due strade qui sotto) invece di far partire il setup.

Cosa fa il setup (via `docker exec … sqlplus`, output con password mascherate):

0. **pre-flight**: verifica che HR e SH siano installati — se mancano, **blocca**;
1. verifica connessione e versione del DB;
2. crea l'utente **`tuner`** con i privilegi del corso (§6.5);
3. concede lettura su dizionario/`V$` e sugli schemi **HR** e **SH** a `corso` e `tuner`;
4. raccoglie le statistiche dell'ottimizzatore su HR e SH;
5. `TRUNCATE` + carica i dati di test in `bench_customers` (+ statistiche);
6. verifica finale.

> Il setup **configura** l'accesso a HR/SH ma non li **installa**. Un **pre-flight**
> controlla che HR e SH esistano e, se mancano, **blocca** la procedura (stato
> `blocked`) mostrando nel terminale le istruzioni per installarli a mano — così non
> ti ritrovi con un ambiente a metà. Installali (è un esercizio del corso, vedi
> [Installare gli schemi demo HR e SH](#installare-gli-schemi-demo-hr-e-sh); SH richiede
> SQLcl) e **rilancia** il setup. Puoi disattivare il blocco con
> `oracle-bench.setup.require-demo-schemas: false`.

### Funziona su entrambe le edizioni

Sì: il setup usa `docker exec … sqlplus` con SQL standard, quindi vale sia per la
**community gvenzl** sia per l'**Oracle 19c ufficiale**. Cambia solo la configurazione.

**Strada A — community gvenzl** (service name `FREEPDB1`):

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
    username: corso
    password: Corso2026
oracle-bench:
  setup:
    container: oracle-corso
    service: FREEPDB1
    system-password: Corso2026
    app-password: Corso2026
```

**Strada B — Oracle 19c ufficiale** (service name `ORCLPDB1`, container diverso):

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/ORCLPDB1
    username: corso
    password: Corso2026
oracle-bench:
  setup:
    container: oracle19c
    service: ORCLPDB1
    system-password: Corso2026
    app-password: Corso2026
```

Le altre chiavi disponibili sotto `oracle-bench.setup`: `docker-path`, `system-user`,
`app-user`, `tuner-user`, `tuner-password`, `seed-rows`.

> ⚠️ Il setup lancia `docker` come sottoprocesso: **avvia l'app da un terminale** in cui
> il comando `docker` è nel PATH (non da un launcher grafico che non erediti il PATH),
> altrimenti il terminale mostrerà "Docker non raggiungibile".

---

## Le dipendenze — `pom.xml`

Spring Boot usa gli **starter**: dipendenze "ombrello" che tirano dentro un set
coerente di librerie con versioni già allineate dal *parent* `spring-boot-starter-parent`.
Non devi indicare le versioni delle librerie Spring: le decide il BOM del parent.

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.6</version>
</parent>
```

- **`spring-boot-starter-web`** — porta Spring MVC + **Tomcat embedded** + Jackson
  (serializzazione JSON). È ciò che rende gli endpoint `@RestController` raggiungibili
  via HTTP e serve i file statici sotto `static/`.
- **`spring-boot-starter-jdbc`** — porta `spring-jdbc` (`JdbcTemplate`, gestione
  transazioni) e, soprattutto, **HikariCP** come connection pool di default. È questo
  starter che fa sì che Spring Boot, vedendo un `spring.datasource.*`, costruisca da
  solo un `DataSource` di tipo Hikari.
- **`ojdbc11`** — il driver JDBC di Oracle. Non è distribuito dentro Spring, va
  dichiarato a parte con la sua versione:

  ```xml
  <dependency>
      <groupId>com.oracle.database.jdbc</groupId>
      <artifactId>ojdbc11</artifactId>
      <version>23.4.0.24.05</version>
  </dependency>
  ```

  **Perché proprio `ojdbc11`?** Oracle pubblica lo stesso driver compilato per diverse
  versioni di Java: `ojdbc8` (Java 8), `ojdbc11` (Java 11+) , `ojdbc17` (Java 17+). Il
  suffisso indica la *baseline* di bytecode, non la versione del database. `ojdbc11`
  gira perfettamente su Java 21 ed è compatibile con i database Oracle moderni
  (19c, 21c, 23ai). È il **thin driver**: 100% Java, non richiede l'installazione del
  client Oracle sulla macchina (a differenza del driver *OCI/thick*, che avrebbe
  bisogno delle librerie native del client).

- **`spring-boot-starter-test`** (scope `test`) — JUnit 5, AssertJ, Mockito per
  eventuali test.

Il plugin **`spring-boot-maven-plugin`** produce il jar "fat" eseguibile con
`java -jar` (tutte le dipendenze incluse) e abilita `mvn spring-boot:run`.

---

## La configurazione — `application.yml` riga per riga

`application.yml` è il file che Spring Boot legge all'avvio da
`src/main/resources/`. Ogni chiave mappa una *property* nota agli auto-configuratori.

```yaml
server:
  port: 8080                     # porta HTTP del Tomcat embedded

spring:
  application:
    name: oracle-bench           # nome logico dell'app (compare nei log/metriche)

  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
    username: corso
    password: Corso2026
    driver-class-name: oracle.jdbc.OracleDriver
    hikari:
      pool-name: oracle-bench-pool
      maximum-pool-size: 5
      minimum-idle: 1
      connection-timeout: 10000

  sql:
    init:
      mode: always
      continue-on-error: true
      schema-locations: classpath:schema.sql

logging:
  level:
    com.example.oraclebench: INFO
```

Chiave per chiave:

- **`server.port`** — su quale porta ascolta Tomcat. Cambiala se la 8080 è occupata.
- **`spring.datasource.url`** — la stringa di connessione JDBC. Vedi la sezione
  dedicata sotto.
- **`spring.datasource.username` / `password`** — le credenziali dell'utente Oracle.
  In produzione **non** vanno scritte in chiaro qui: vedi
  [Governare la configurazione](#governare-la-configurazione-profili-segreti-tuning).
- **`spring.datasource.driver-class-name`** — la classe del driver JDBC,
  `oracle.jdbc.OracleDriver`. Spring di solito la deduce dall'URL, ma indicarla è
  esplicito e non ambiguo.
- **`spring.datasource.hikari.*`** — i parametri del connection pool. Sezione dedicata
  sotto.
- **`spring.sql.init.*`** — l'inizializzatore di schema di Spring Boot:
  - `mode: always` → esegue gli script anche su un database "vero" (di default vengono
    eseguiti solo su DB embedded come H2).
  - `schema-locations: classpath:schema.sql` → quale file DDL eseguire all'avvio.
  - `continue-on-error: true` → **fondamentale qui**: Oracle non ha
    `CREATE TABLE IF NOT EXISTS`, quindi al secondo avvio la `CREATE TABLE` fallisce con
    `ORA-00955: name already used`. Con `continue-on-error` l'errore viene loggato e
    ignorato, e l'app parte comunque. (In alternativa si userebbe un blocco PL/SQL che
    controlla l'esistenza, o uno strumento di migrazione come Flyway/Liquibase.)
- **`logging.level.com.example.oraclebench: INFO`** — alza il livello di log del nostro
  package così da vedere **ogni SQL eseguita**. Sblocca la riga commentata
  `com.zaxxer.hikari: DEBUG` per vedere anche il ciclo di vita del pool.

---

## La stringa di connessione, in dettaglio

```
jdbc:oracle:thin:@//localhost:1521/FREEPDB1
└──┬─┘ └──┬─┘ └┬─┘ │ └───┬───┘ └┬─┘ └──┬───┘
  1      2     3   4     5      6      7
```

1. **`jdbc:`** — è un URL JDBC. Il `DriverManager` lo usa per scegliere il driver.
2. **`oracle:`** — sotto-protocollo: database Oracle.
3. **`thin:`** — il tipo di driver. **Thin** = Type 4, puro Java, parla direttamente
   il protocollo di rete di Oracle (TNS/Net) senza client nativo. L'alternativa è
   `oci:` (thick), che richiede l'Oracle Client installato: qui non ci serve.
4. **`@`** — separatore tra il "come" (driver) e il "dove" (indirizzo).
5. **`//localhost:1521`** — host e porta del **listener** Oracle. La `1521` è la porta
   standard del listener.
6. **la `/`** dopo la porta — introduce un **service name** (formato *EZConnect*:
   `//host:porta/servizio`). È la parte che distingue service name da SID.
7. **`FREEPDB1`** — il **service name** della PDB a cui collegarsi.

Confronto con le altre forme che potresti incontrare:

```
# Service name (EZConnect) — quello che usiamo:
jdbc:oracle:thin:@//localhost:1521/FREEPDB1

# SID (vecchio stile, con i due punti prima del nome):
jdbc:oracle:thin:@localhost:1521:XE

# Descrittore TNS completo (utile con RAC/failover/opzioni di rete):
jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=localhost)(PORT=1521))
                   (CONNECT_DATA=(SERVICE_NAME=FREEPDB1)))
```

**Perché Oracle Database e non un altro DB?** Perché il vero soggetto del progetto è
il comportamento *specifico* di Oracle: il concetto di **soft parse vs hard parse**,
la **shared pool** dove i cursori vengono condivisi, e la vista **`V$SQL`** che espone
il testo SQL e il suo **`sql_id`**. Sono queste peculiarità che permettono di
*dimostrare* l'effetto delle bind variables. Su un altro DBMS le lezioni sarebbero
diverse.

---

## HikariCP: cos'è, perché, come è configurato

### Cos'è un connection pool

Aprire una connessione a Oracle è **costoso**: handshake TCP, autenticazione, creazione
di una *sessione* server (con la sua memoria PGA). Se aprissi e chiudessi una
connessione a ogni richiesta HTTP, il tempo di connessione dominerebbe qualsiasi
misura — e il benchmark sarebbe inutile.

Un **connection pool** apre un piccolo insieme di connessioni **una volta** e le tiene
pronte. Quando il codice chiede una connessione, il pool gliene "presta" una già
aperta; quando fa `close()`, la connessione **non** viene chiusa davvero ma
**restituita** al pool per il prossimo utilizzo.

**HikariCP** è il pool JDBC più veloce e affidabile in circolazione ed è quello di
default in Spring Boot: basta avere `spring-boot-starter-jdbc` e delle property
`spring.datasource.*` perché Spring costruisca un `HikariDataSource`.

### Perché è configurato così

```yaml
hikari:
  pool-name: oracle-bench-pool   # nome leggibile nei log e nei thread ("HikariPool-...")
  maximum-pool-size: 5           # al massimo 5 connessioni fisiche aperte insieme
  minimum-idle: 1                # tieni almeno 1 connessione pronta anche a riposo
  connection-timeout: 10000      # attesa max (ms) per ottenere una connessione dal pool
```

- **`maximum-pool-size: 5`** — il tetto di connessioni *fisiche* verso Oracle. È anche
  il **grado massimo di concorrenza reale** verso il DB: la sesta richiesta simultanea
  aspetta che una delle 5 si liberi. Per una demo su `localhost` cinque bastano e
  avanzano. **Effetto sul benchmark:** il pulsante *"Esegui 10 volte"* lancia comunque
  le query in sequenza (una alla volta), quindi una sola connessione viene riusata 10
  volte — condizione ideale per osservare il riuso del cursore.
- **`minimum-idle: 1`** — quante connessioni tenere aperte "a vuoto" quando non c'è
  carico. Con `1`, la prima query dopo un periodo di inattività trova già una
  connessione calda (niente costo di apertura che sporcherebbe la misura). Impostare
  `minimum-idle = maximum-pool-size` dà un pool a dimensione fissa.
- **`connection-timeout: 10000`** — quanti millisecondi un thread aspetta una
  connessione libera prima di ricevere `SQLTransientConnectionException`. Protegge
  l'app dal restare bloccata all'infinito se il DB è irraggiungibile o il pool esaurito.

### Perché una dimensione *piccola* è spesso quella giusta

Controintuitivamente, pool enormi peggiorano le cose. Ogni connessione è una **sessione
Oracle reale** che consuma memoria sul server ed è soggetta ai limiti dell'istanza
(`sessions`, `processes`). Oltre un certo punto le connessioni competono per CPU e
lock invece di aumentare il throughput. La regola pratica di HikariCP per il
dimensionamento orientato al throughput è:

```
connessioni ≈ (numero_di_core × 2) + numero_di_dischi
```

Meglio un pool piccolo e ben usato che uno grande e affamato di risorse.

### Altri parametri utili (non usati qui, ma da conoscere)

| parametro | a cosa serve |
|-----------|--------------|
| `idle-timeout` | dopo quanto una connessione *in eccesso* rispetto a `minimum-idle` viene chiusa |
| `max-lifetime` | età massima di una connessione prima di essere ritirata (tienila **sotto** il timeout di rete/DB) |
| `keepalive-time` | ping periodico per evitare che firewall/DB chiudano connessioni idle |
| `connection-test-query` | query di validazione (di norma non serve con driver JDBC4 come ojdbc) |

---

## Governare la configurazione (profili, segreti, tuning)

**Non tenere le password in chiaro nel repo.** Spring Boot legge le property da più
sorgenti, con priorità (le variabili d'ambiente vincono sul file). Puoi quindi lasciare
`application.yml` con valori di sviluppo e sovrascriverli così:

```bash
# le variabili d'ambiente sovrascrivono spring.datasource.*
export SPRING_DATASOURCE_USERNAME=corso
export SPRING_DATASOURCE_PASSWORD='Corso2026'
export SPRING_DATASOURCE_URL='jdbc:oracle:thin:@//db-prod:1521/FREEPDB1'
./mvnw spring-boot:run
```

La regola di *relaxed binding*: `spring.datasource.url` ⇄ `SPRING_DATASOURCE_URL`
(punti → underscore, maiuscolo).

**Profili** per ambienti diversi: crea `application-dev.yml`, `application-prod.yml` e
attivali con `--spring.profiles.active=prod` (o `SPRING_PROFILES_ACTIVE=prod`). Le
chiavi del profilo attivo sovrascrivono quelle di base.

**Cosa toccheresti passando in produzione:**

- alzare `maximum-pool-size` in base a core del DB e concorrenza attesa (misura, non
  tirare a indovinare);
- impostare `max-lifetime` (es. 30 min) e `keepalive-time` per reti con firewall;
- esternalizzare le credenziali (env var o un secret manager/Vault);
- sostituire lo `schema.sql` "best effort" con **Flyway** o **Liquibase** per
  migrazioni versionate e ripetibili;
- valutare l'aumento del `server.tomcat.threads.max` se il collo di bottiglia sono i
  thread HTTP e non il DB.

---

## Interfaccia web

La UI (`/`) è organizzata in **tab**:

- **Benchmark** — select delle query, campo `p`, checkbox **"Includi dati"** e uno
  switch **Performance / Dati**: la vista *Performance* accumula i tempi (verde =
  minimo, rosso = massimo), la vista *Dati* mostra le righe dell'ultima esecuzione.
- **Tabelle** — in cima la **checklist Dataset del corso** (HR, SH, copie di lavoro,
  progetto) con stato **verde/rosso**: verde = presente e visibile, rosso = mancante o
  non visibile all'utente connesso. Sotto, l'elenco di **tutte le tabelle visibili**
  (il tuo schema + **HR** + **SH**): cliccandone una qualsiasi si apre lo stesso
  dettaglio (statistiche, colonne, indici, **COUNT(\*) esatto**). Lo switch **"Mostra
  tipi e caratteristiche colonne"** aggiunge tipo, nullabilità e PK. Le azioni di
  scrittura (**Aggiorna statistiche**, **crea/elimina indice**, **genera N righe** in
  `bench_customers`) sono attive solo sulle tabelle del tuo schema.
- **Query custom** — scegli una tabella (tuo schema + HR + SH), spunta le colonne o
  usa **Tutte le colonne**, **Costruisci SELECT** e modifica la query a mano; solo
  query in lettura (SELECT/WITH, connessione read-only). Risultati con righe, ms e
  `sql_id`. Sulla **stessa query dell'editor** puoi lanciare **Explain Plan** e
  **Autotrace**: così analizzi qualsiasi query su HR/SH (join, indici compositi,
  aggregazioni…), non solo le query predefinite.
- **Guida** (icona ⓘ in alto a destra) — guida rapida con il **glossario dei comandi
  SQL** del corso (dal cheat sheet): comandi client SQL\*Plus con il loro equivalente
  SQL, query sul dizionario dati, equivalenze MySQL→Oracle. Ogni snippet eseguibile ha
  un pulsante **Inserisci** che lo porta nell'editor Query custom.
- **SQL Tuning** — il laboratorio vero e proprio:
  - **Piano di esecuzione**: scegli una query e lancia **Explain Plan** (stima, non
    esegue) o **Autotrace** (esegue con `STATISTICS_LEVEL=ALL` e mostra il piano reale
    con A-Rows più l'I/O logico consumato).
  - **Statistiche cumulative V$SQL**: dato un `sql_id`, mostra `executions`,
    `parse_calls`, `buffer_gets`, `disk_reads`, numero di piani distinti, ecc.
  - **Catalogo anti-pattern**: casi "lento vs buono" (funzione su colonna, wildcard
    iniziale, conversione di tipo, `SELECT *`) con i piani a confronto.

## REST API

### Query di benchmark

| metodo | endpoint                              | descrizione |
|--------|---------------------------------------|-------------|
| GET    | `/api/queries`                        | elenco delle query disponibili (id + SQL) |
| GET    | `/api/query?id=<n>&p=<val>&data=true` | esegue la query `n`; `p` e `data` opzionali |

Risposta base:

```json
{ "id": 1, "rows": 1, "elapsedMs": 0.842, "sqlId": "abc123def456" }
```

Con `data=true` la risposta include anche i dati (fino a 500 righe materializzate):

```json
{ "id": 4, "rows": 3, "elapsedMs": 1.2, "sqlId": "…",
  "columns": ["ID","NAME","EMAIL","CREATED_AT"],
  "data": [ { "ID": 1, "NAME": "Mario Rossi", "EMAIL": "…", "CREATED_AT": "…" } ],
  "truncated": false }
```

- `elapsedMs` è misurato con `System.nanoTime()` **attorno alla sola
  esecuzione + fetch** del ResultSet. Anche con `data=true` **tutte** le righe
  vengono fetchate (il tempo resta rappresentativo); solo le prime 500 finiscono
  nel JSON e `truncated` diventa `true` se ce ne sono di più.
- `sqlId` è recuperato con una seconda query su `V$SQL` cercando il testo esatto
  (`WHERE sql_text = ?`); è vuoto se non trovato.

### Esplorazione dello schema

| metodo | endpoint                                | descrizione |
|--------|-----------------------------------------|-------------|
| GET    | `/api/schema/datasets`                  | checklist HR/SH/copie/progetto: presente o mancante (+ num_rows) |
| GET    | `/api/schema/tables`                    | tabelle visibili (tuo schema + **HR** + **SH**), con owner |
| GET    | `/api/schema/tables/{owner}/{name}`     | dettaglio: statistiche, colonne, indici, PK (`own` = tabella tua) |
| GET    | `/api/schema/tables/{owner}/{name}/count` | `COUNT(*)` esatto (da contrapporre alle stats) |

La scheda **Tabelle** elenca ora anche HR e SH: cliccando una qualsiasi tabella si apre
lo stesso pannello di dettaglio (statistiche, colonne, indici, `COUNT(*)`). Le azioni di
**scrittura** (crea/elimina indice, aggiorna statistiche) restano disponibili solo sulle
tabelle del tuo schema; la dimensione segmento è nota solo per le tue tabelle.

### Query custom

| metodo | endpoint                                     | descrizione |
|--------|----------------------------------------------|-------------|
| GET    | `/api/custom/tables`                         | tabelle visibili (tuo schema + HR + SH) per il picker |
| GET    | `/api/custom/columns?owner=<O>&table=<T>`    | colonne di una tabella |
| POST   | `/api/custom/run` (body `{"sql":"…"}`)       | esegue una SELECT/WITH in **sola lettura** |

Il runner custom accetta **un solo statement** SELECT/WITH, apre la connessione in
read-only e rifiuta DML/DDL: è un editor didattico, non una console amministrativa.

Oltre alle SELECT, il runner riconosce e **traduce in SQL** alcuni comandi in stile
SQL\*Plus/SQLcl utili a sviluppatori e DBA:

| comando            | equivale a |
|--------------------|-----------|
| `DESC [schema.]tab` | struttura (colonne, tipo, NULL?) da `ALL_TAB_COLUMNS` |
| `SHOW USER`        | `SELECT USER FROM dual` |
| `SHOW CON_NAME`    | container/PDB corrente |
| `SHOW PARAMETER [x]` | `V$PARAMETER` (filtra per nome) |
| `SHOW PDBS`        | `V$PDBS` |
| `SHOW TABLES`      | `USER_TABLES` |
| `DDL oggetto`      | `DBMS_METADATA.GET_DDL(...)` |

I comandi puramente client (`SET AUTOTRACE`, `SET TIMING`, ecc.) restano nella Guida
con i loro equivalenti, perché non hanno senso via JDBC.

Le "righe" in `/api/schema/tables` vengono da `USER_TABLES.NUM_ROWS`, cioè dalle
**statistiche dell'ottimizzatore** (possono essere `null` o stantie); la
dimensione da `USER_SEGMENTS.BYTES`. L'endpoint `/count` esegue un `COUNT(*)`
reale — il confronto tra i due valori è una lezione di tuning in sé.

### Strumenti di tuning

| metodo | endpoint                                             | descrizione |
|--------|------------------------------------------------------|-------------|
| GET    | `/api/tuning/plan?id=<n>&p=<v>`                       | `EXPLAIN PLAN` (stima) di una query predefinita |
| GET    | `/api/tuning/autotrace?id=<n>&p=<v>`                  | esegue + piano reale (A-Rows) + I/O logico |
| POST   | `/api/tuning/plan` (body `{"sql":"…"}`)              | `EXPLAIN PLAN` di una SELECT libera (read-only) |
| POST   | `/api/tuning/autotrace` (body `{"sql":"…"}`)        | autotrace di una SELECT libera (read-only) |
| GET    | `/api/tuning/sqlstats/{sqlId}`                        | statistiche cumulative da `V$SQL` |
| GET    | `/api/tuning/antipatterns`                            | catalogo dei casi "lento vs buono" |
| GET    | `/api/tuning/antipatterns/{id}/plan?variant=bad\|good`     | `EXPLAIN PLAN` di una variante |
| GET    | `/api/tuning/antipatterns/{id}/autotrace?variant=bad\|good`| autotrace di una variante |

Il catalogo anti-pattern è **lato server** (`src/main/resources/antipatterns.json`):
il client indica solo id e variante, così non viene mai eseguita SQL arbitraria dal
browser.

### Lab: seeding e indici

| metodo | endpoint                                    | descrizione |
|--------|---------------------------------------------|-------------|
| POST   | `/api/admin/seed?n=<N>`                      | inserisce N righe fittizie (batch) in `bench_customers` |
| POST   | `/api/admin/truncate`                        | svuota `bench_customers` |
| POST   | `/api/admin/gather-stats?table=<T>`          | ricalcola le statistiche (`DBMS_STATS`) |
| POST   | `/api/admin/index?table=<T>&column=<C>`      | crea un indice non univoco su `T(C)` |
| DELETE | `/api/admin/index/{name}`                    | elimina un indice (rifiuta quelli che reggono un vincolo) |

Nomi di tabella/colonna/indice sono **validati** contro il dizionario dati e un
pattern di identificatore prima di finire in una `CREATE INDEX`/`COUNT(*)`, perché un
identificatore non può essere una bind variable.

### Flusso didattico consigliato

1. **Tabelle → Genera dati**: es. 50.000 righe in `bench_customers`. Le email sono
   sequenziali (`cliente1@…`, `cliente2@…`, uniche → **selettive**); i nomi sono a
   bassa cardinalità (144 valori distinti → **non selettivi**), di proposito.
2. **Tabelle → Aggiorna statistiche** (`DBMS_STATS`): confronta le "righe (stats)" con
   il **COUNT(\*) esatto**.
3. **SQL Tuning → Autotrace** della **query #6** (`WHERE email = ?`, es.
   `p = cliente25000@example.com`): **FULL TABLE SCAN**, ~**548 logical reads** per 1 riga.
4. **Tabelle → Crea indice** su `EMAIL`, poi ri-esegui l'autotrace della #6: il piano
   passa a **INDEX RANGE SCAN**, **3 logical reads** (numeri reali dal test E2E).
5. **Contrappunto didattico**: prova lo stesso con la **query #5** (`WHERE name = ?`) e
   un indice su `NAME`: il piano **resta FULL SCAN**, perché `NAME` non è selettivo
   (~347 righe per valore) — l'ottimizzatore fa bene a ignorare l'indice. È la lezione
   su *selettività e clustering*, non un bug.
6. **Catalogo anti-pattern**: confronta i piani di ogni caso lento vs buono
   (la versione "buona" di *func-on-column* usa l'indice su `NAME` perché proietta solo
   `id, name` → risoluzione *index-only*).

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

---

## Le query — `queries.sql`

Formato: una riga `--#<n>` introduce la query numero `n`; le righe successive
(fino al marcatore seguente) sono il corpo SQL.

- Un `?` nel corpo è una **bind variable**: il parametro `p` della richiesta vi
  viene associato con `PreparedStatement.setString`.
- La **query 99** è l'unica eccezione: il token `__P__` viene **concatenato**
  nella stringa SQL (nessun bind). Ad ogni valore diverso corrisponde un testo
  SQL diverso → **hard parse** e nuovo `sql_id` ogni volta, oltre a essere
  vulnerabile a **SQL injection**. Serve proprio a dimostrarlo.

### Perché è importante: soft parse vs hard parse

Quando Oracle riceve una SQL, ne calcola un hash e cerca nella **shared pool** un
cursore già pronto con lo stesso testo:

- **testo identico** (è il caso delle bind variables, dove il testo resta
  `... WHERE name = ?` qualunque sia il valore) → il cursore viene **riusato**:
  è un **soft parse**, veloce, e il `sql_id` **non cambia**.
- **testo diverso a ogni esecuzione** (è il caso della concatenazione: `... = 'Mario'`,
  poi `... = 'Anna'`, ...) → nessun cursore riutilizzabile: Oracle deve rifare
  ottimizzazione e piano da zero, un **hard parse**, con `sql_id` **nuovo ogni volta**.
  Su carichi reali questo satura la shared pool e degrada tutto il database.

### Demo suggerita

1. Esegui più volte la query **#5** con lo stesso `p` (es. `Mario Rossi`):
   il `sql_id` resta costante (cursore condiviso, soft parse).
2. Esegui la query **#99** cambiando `p` ogni volta: il `sql_id` cambia
   ad ogni esecuzione (hard parse). Prova un valore come
   `x' OR '1'='1` per vedere l'iniezione.

---

## Log

Ogni SQL eseguita (incluse le lookup su `V$SQL`) viene loggata dal package
`com.example.oraclebench`. Per vedere anche il ciclo di vita del pool, togli il
commento a `com.zaxxer.hikari: DEBUG` in `application.yml`.
