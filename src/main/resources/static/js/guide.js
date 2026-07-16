// Guida modal: quick reference + SQL command glossary (from glossario-esempio.md).
// Runnable snippets get an "Inserisci" button that drops the SQL into the
// Query custom editor; client-only SQL*Plus/SQLcl commands are labelled.

import { esc } from './util.js';
import { openTab } from './tabs.js';

const GUIDE = [
    {
        title: 'Comandi client SQL*Plus / SQLcl',
        note: 'Non girano nell\'editor (sono del client). Dove esiste, usa l\'equivalente SQL con "Inserisci".',
        rows: [
            { cmd: 'SHOW USER', desc: 'Utente corrente', sql: "SELECT USER FROM dual" },
            { cmd: 'SHOW CON_NAME', desc: 'Container/PDB corrente', sql: "SELECT SYS_CONTEXT('USERENV','CON_NAME') AS con_name FROM dual" },
            { cmd: 'DESC <tabella>', desc: 'Struttura di una tabella', sql: "SELECT column_name, data_type, nullable\nFROM all_tab_columns\nWHERE owner = 'HR' AND table_name = 'EMPLOYEES'\nORDER BY column_id" },
            { cmd: 'SHOW PARAMETER cursor_sharing', desc: 'Valore di un parametro (Modulo 11)', sql: "SELECT name, value FROM v$parameter WHERE name = 'cursor_sharing'" },
            { cmd: 'SET AUTOTRACE ON', desc: 'Piano + statistiche dopo la query', client: true, hint: 'Usa la tab SQL Tuning → Autotrace' },
            { cmd: 'SET TIMING ON', desc: 'Cronometro degli statement', client: true, hint: 'Qui il tempo è già in ms nei risultati' }
        ]
    },
    {
        title: 'Dizionario dati — girano nell\'editor',
        note: 'In Oracle non c\'è SHOW TABLES: si interroga il dizionario (USER_ = tuoi, ALL_ = visibili, DBA_ = tutti).',
        rows: [
            { cmd: 'Lista tabelle (mie)', desc: 'Equivalente di SHOW TABLES', sql: "SELECT table_name FROM user_tables ORDER BY table_name" },
            { cmd: 'Tabelle HR e SH', desc: 'Oggetti visibili di altri schemi', sql: "SELECT owner, table_name FROM all_tables WHERE owner IN ('HR','SH') ORDER BY owner, table_name" },
            { cmd: 'Colonne di una tabella', desc: 'Versione query di DESC', sql: "SELECT column_name, data_type FROM all_tab_columns\nWHERE owner = 'SH' AND table_name = 'SALES' ORDER BY column_id" },
            { cmd: 'Indici di una tabella', desc: 'Con colonne indicizzate', sql: "SELECT index_name, column_name, column_position\nFROM all_ind_columns WHERE table_owner = 'SH' AND table_name = 'SALES'\nORDER BY index_name, column_position" },
            { cmd: 'Conteggio righe', desc: 'Prima di SELECT * su tabelle grosse!', sql: "SELECT COUNT(*) FROM sh.sales" },
            { cmd: 'Prime N righe', desc: 'LIMIT di MySQL → FETCH FIRST', sql: "SELECT * FROM hr.employees FETCH FIRST 10 ROWS ONLY" },
            { cmd: 'DDL di un oggetto', desc: 'Come SHOW CREATE TABLE', sql: "SELECT DBMS_METADATA.GET_DDL('TABLE','EMPLOYEES','HR') FROM dual" },
            { cmd: 'Sessioni attive', desc: 'Come SHOW PROCESSLIST', sql: "SELECT sid, serial#, username, status FROM v$session WHERE username IS NOT NULL" }
        ]
    },
    {
        title: 'Equivalenze MySQL/MariaDB → Oracle',
        note: 'Stessi bisogni, sintassi diversa.',
        rows: [
            { cmd: 'SHOW TABLES;', desc: '→ user_tables', sql: "SELECT table_name FROM user_tables" },
            { cmd: 'USE db;', desc: '→ cambia schema di default', sql: "ALTER SESSION SET CURRENT_SCHEMA = SH" },
            { cmd: 'SELECT NOW();', desc: '→ dual + SYSTIMESTAMP', sql: "SELECT SYSTIMESTAMP FROM dual" },
            { cmd: 'LIMIT 10', desc: '→ FETCH FIRST 10 ROWS ONLY', sql: "SELECT * FROM hr.departments FETCH FIRST 10 ROWS ONLY" }
        ]
    }
];

function renderGuide() {
    document.getElementById('guideBody').innerHTML = GUIDE.map(sec => `
        <h2>${esc(sec.title)}</h2>
        <p class="note">${esc(sec.note)}</p>
        ${sec.rows.map(r => `
            <div class="snip">
                <code>${esc(r.cmd)}</code>
                <span class="note" style="flex:1">${esc(r.desc)}${r.hint ? ' — ' + esc(r.hint) : ''}</span>
                ${r.sql ? `<button class="small snipBtn" data-sql="${esc(r.sql)}">Inserisci</button>`
                        : (r.client ? '<span class="pill">client</span>' : '')}
            </div>`).join('')}
    `).join('');
    document.querySelectorAll('.snipBtn').forEach(b => b.addEventListener('click', () => {
        openTab('custom');
        document.getElementById('customSql').value =
            b.dataset.sql.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
        closeGuide();
    }));
}

/** Opens the guide modal (rendering it lazily). */
export function openGuide() {
    renderGuide();
    document.getElementById('guideOverlay').classList.add('open');
}

function closeGuide() {
    document.getElementById('guideOverlay').classList.remove('open');
}

/** Wires the guide open/close controls. */
export function initGuide() {
    document.getElementById('guideBtn').addEventListener('click', openGuide);
    document.getElementById('guideClose').addEventListener('click', closeGuide);
    document.getElementById('guideOverlay').addEventListener('click', e => { if (e.target.id === 'guideOverlay') closeGuide(); });
}
