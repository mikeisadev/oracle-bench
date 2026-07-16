package com.example.oraclebench.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One-click course setup: creates the {@code tuner} user, wires HR/SH read access
 * and dictionary/V$ access, gathers optimizer statistics and loads test data —
 * all by shelling into the Oracle container with {@code docker exec ... sqlplus}.
 *
 * <p>Every command and every line of output is streamed to the caller (a terminal
 * in the browser). Commands are fixed (no user input is interpolated into the
 * shell); only the configured, self-generated SQL runs.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    // ANSI colours (rendered by xterm.js in the browser)
    private static final String RESET = "[0m", BOLD = "[1m", DIM = "[2m";
    private static final String CYAN = "[36m", GREEN = "[32m", RED = "[31m", YELLOW = "[33m";

    private final SetupProperties cfg;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SetupService(SetupProperties cfg) {
        this.cfg = cfg;
    }

    /** One setup step: a title, the connect user, the SQL body, and criticality. */
    private record Step(String title, String user, String password, String sql, boolean critical) {
    }

    /** Whether Docker is reachable and the configured container is running. */
    public record SetupStatus(boolean dockerAvailable, boolean containerRunning,
                              String container, String service) {
    }

    /** Probes {@code docker inspect} to tell if the lab container is up. */
    public SetupStatus status() {
        try {
            Process p = new ProcessBuilder(cfg.getDockerPath(), "inspect", "-f", "{{.State.Running}}", cfg.getContainer())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            boolean running = code == 0 && out.contains("true");
            return new SetupStatus(true, running, cfg.getContainer(), cfg.getService());
        } catch (java.io.IOException e) {
            return new SetupStatus(false, false, cfg.getContainer(), cfg.getService());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SetupStatus(false, false, cfg.getContainer(), cfg.getService());
        }
    }

    public boolean tryLock() {
        return running.compareAndSet(false, true);
    }

    public void unlock() {
        running.set(false);
    }

    /** Runs the whole sequence, emitting each terminal line to {@code out}. */
    public void run(Consumer<String> out) {
        List<Step> steps = steps();
        log.info("Avvio setup: {} passi sul container {}", steps.size(), cfg.getContainer());
        out.accept(BOLD + CYAN + "Setup ambiente ORA1150 — container '" + cfg.getContainer() + "'" + RESET);
        out.accept(DIM + "Passi: " + steps.size() + ". Le password sono mascherate nell'output." + RESET);

        int n = 0;
        for (Step s : steps) {
            n++;
            out.accept("");
            out.accept(BOLD + CYAN + "══ [" + n + "/" + steps.size() + "] " + s.title() + " ══" + RESET);
            out.accept(DIM + "$ docker exec " + cfg.getContainer() + " sqlplus -s "
                    + s.user() + "/****@//localhost:1521/" + cfg.getService() + RESET);
            for (String line : mask(s.sql()).strip().split("\n")) {
                out.accept(DIM + "  " + line + RESET);
            }

            int code = execSqlplus(s, out);

            if (code == 0) {
                out.accept(GREEN + "✓ step completato" + RESET);
            } else {
                out.accept(RED + "✗ step terminato con codice " + code + RESET);
                if (s.critical()) {
                    out.accept(RED + BOLD + "Passo critico fallito: setup interrotto." + RESET);
                    return;
                }
            }
        }
        out.accept("");
        out.accept(BOLD + GREEN + "✔ Setup completato. Ora puoi connettere l'app come '"
                + cfg.getTunerUser() + "' o usare '" + cfg.getAppUser() + "'." + RESET);
        out.accept(YELLOW + "Ricorda: riavvia l'app se cambi utente in application.yml." + RESET);
    }

    // ---------- steps ----------

    private List<Step> steps() {
        String sys = cfg.getSystemUser(), sysPw = cfg.getSystemPassword();
        String app = cfg.getAppUser(), appPw = cfg.getAppPassword();

        List<Step> s = new ArrayList<>();

        s.add(new Step("Verifica connessione e versione", sys, sysPw, """
                SELECT banner_full FROM v$version WHERE ROWNUM = 1;
                SELECT 'Connesso come ' || USER || ' @ ' || SYS_CONTEXT('USERENV','CON_NAME') AS stato FROM dual;
                """, true));

        s.add(new Step("Creazione utente " + cfg.getTunerUser() + " con privilegi (abstract §6.5)", sys, sysPw, """
                DECLARE u NUMBER; BEGIN
                  SELECT COUNT(*) INTO u FROM dba_users WHERE username = '%TUNER%';
                  IF u > 0 THEN
                    EXECUTE IMMEDIATE 'DROP USER %tuner% CASCADE';
                    DBMS_OUTPUT.PUT_LINE('Utente %tuner% preesistente rimosso.');
                  END IF;
                END;
                /
                CREATE USER %tuner% IDENTIFIED BY "%tunerpw%" QUOTA UNLIMITED ON users;
                GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SYNONYM TO %tuner%;
                GRANT SELECT ANY DICTIONARY TO %tuner%;
                GRANT EXECUTE ON DBMS_MONITOR TO %tuner%;
                GRANT ADMINISTER SQL MANAGEMENT OBJECT TO %tuner%;
                PROMPT Utente %tuner% creato con i privilegi del corso.
                """
                .replace("%TUNER%", cfg.getTunerUser().toUpperCase())
                .replace("%tuner%", cfg.getTunerUser())
                .replace("%tunerpw%", cfg.getTunerPassword()), true));

        s.add(new Step("Accesso a dizionario/V$ e agli schemi HR e SH", sys, sysPw, """
                GRANT SELECT ANY DICTIONARY TO %app%;
                BEGIN
                  FOR t IN (SELECT owner, table_name FROM dba_tables WHERE owner IN ('HR','SH')) LOOP
                    BEGIN
                      EXECUTE IMMEDIATE 'GRANT SELECT ON '||t.owner||'.'||t.table_name||' TO %app%';
                      EXECUTE IMMEDIATE 'GRANT SELECT ON '||t.owner||'.'||t.table_name||' TO %tuner%';
                    EXCEPTION WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE('  skip '||t.owner||'.'||t.table_name||': '||SQLERRM);
                    END;
                  END LOOP;
                  DBMS_OUTPUT.PUT_LINE('Grant SELECT su HR/SH assegnati a %app% e %tuner%.');
                END;
                /
                """
                .replace("%app%", app)
                .replace("%tuner%", cfg.getTunerUser()), false));

        s.add(new Step("Statistiche ottimizzatore su HR e SH (può richiedere qualche istante)", sys, sysPw, """
                BEGIN
                  FOR sc IN (SELECT username FROM dba_users WHERE username IN ('HR','SH')) LOOP
                    DBMS_OUTPUT.PUT_LINE('Raccolgo statistiche schema '||sc.username||'...');
                    DBMS_STATS.GATHER_SCHEMA_STATS(sc.username);
                    DBMS_OUTPUT.PUT_LINE('  fatto: '||sc.username);
                  END LOOP;
                END;
                /
                """, false));

        s.add(new Step("Dati di test in bench_customers (" + cfg.getSeedRows() + " righe) + statistiche", app, appPw, """
                TRUNCATE TABLE bench_customers;
                INSERT INTO bench_customers (name, email)
                SELECT DECODE(MOD(LEVEL,12),
                         0,'Mario Rossi',1,'Luca Bianchi',2,'Giulia Verdi',3,'Anna Russo',
                         4,'Marco Ferrari',5,'Sara Esposito',6,'Paolo Romano',7,'Elena Colombo',
                         8,'Davide Ricci',9,'Chiara Marino',10,'Andrea Greco','Francesca Bruno'),
                       'cliente'||LEVEL||'@example.com'
                FROM dual CONNECT BY LEVEL <= %rows%;
                COMMIT;
                EXEC DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCH_CUSTOMERS');
                SELECT COUNT(*) AS righe_bench_customers FROM bench_customers;
                """.replace("%rows%", String.valueOf(cfg.getSeedRows())), false));

        s.add(new Step("Verifica finale", sys, sysPw, """
                SELECT 'utente ' || username || ' presente' AS controllo FROM dba_users WHERE username = '%TUNER%';
                SELECT owner AS schema_demo, COUNT(*) AS tabelle FROM dba_tables
                WHERE owner IN ('HR','SH') GROUP BY owner ORDER BY owner;
                """.replace("%TUNER%", cfg.getTunerUser().toUpperCase()), false));

        return s;
    }

    // ---------- execution ----------

    private int execSqlplus(Step step, Consumer<String> out) {
        String connect = step.user() + "/" + step.password() + "@//localhost:1521/" + cfg.getService();
        String script = "sqlplus -s " + connect + " <<'OBSQL'\n"
                + "WHENEVER SQLERROR CONTINUE\n"
                + "SET ECHO OFF FEEDBACK ON PAGESIZE 200 LINESIZE 150 SERVEROUTPUT ON\n"
                + step.sql().strip() + "\n"
                + "EXIT\n"
                + "OBSQL\n";
        return exec(List.of(cfg.getDockerPath(), "exec", cfg.getContainer(), "bash", "-lc", script), out);
    }

    private int exec(List<String> command, Consumer<String> out) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        out.accept(mask(line));
                    }
                }
            }
            return p.waitFor();
        } catch (java.io.IOException e) {
            out.accept(RED + "Impossibile avviare '" + cfg.getDockerPath() + "': " + e.getMessage() + RESET);
            out.accept(YELLOW + "Verifica che Docker sia installato e nel PATH del processo dell'app." + RESET);
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.accept(RED + "Interrotto." + RESET);
            return -1;
        }
    }

    /** Hides the configured passwords from any streamed line. */
    private String mask(String s) {
        return s
                .replace(cfg.getTunerPassword(), "****")
                .replace(cfg.getSystemPassword(), "****")
                .replace(cfg.getAppPassword(), "****");
    }
}
