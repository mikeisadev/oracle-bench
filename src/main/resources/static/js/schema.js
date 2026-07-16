// Tabelle tab: course-dataset readiness (green/red), the user's tables with a
// detail panel (stats, columns, indexes), plus seeding / index / gather-stats.

import { fmt, esc } from './util.js';

let selectedTable = null;
const tableList = document.getElementById('tableList');
const tableDetail = document.getElementById('tableDetail');
const showColMeta = document.getElementById('showColMeta');
const seedOut = document.getElementById('seedOut');

async function loadDatasets() {
    const box = document.getElementById('datasets');
    const res = await fetch('/api/schema/datasets');
    const groups = await res.json();
    if (!res.ok) { box.innerHTML = `<p class="note">Errore: ${esc(groups.error)}</p>`; return; }
    box.innerHTML = groups.map(g => `
        <div class="dsgroup">
            <div class="gt">${esc(g.group)} <span class="muted">(${esc(g.owner)})</span></div>
            <div class="chips">${g.items.map(it => {
                const cls = it.present ? 'ok' : 'miss';
                const mark = it.present ? '✓' : '✗';
                const rows = it.present && it.numRows !== null ? `<span class="n">${it.numRows}</span>` : '';
                const exp = (!it.present && it.expectedRows) ? `<span class="n">~${it.expectedRows}</span>` : '';
                return `<span class="chip ${cls}">${mark} ${esc(it.name)} ${rows}${exp}</span>`;
            }).join('')}</div>
        </div>`).join('');
}

async function loadTables() {
    tableList.innerHTML = '<div class="item muted">Caricamento…</div>';
    const res = await fetch('/api/schema/tables');
    const data = await res.json();
    if (!res.ok) { tableList.innerHTML = `<div class="item muted">Errore: ${esc(data.error)}</div>`; return; }
    if (!data.length) { tableList.innerHTML = '<div class="item muted">Nessuna tabella per questo utente.</div>'; return; }
    tableList.innerHTML = '';
    data.forEach(t => {
        const div = document.createElement('div');
        div.className = 'item'; div.dataset.name = t.tableName;
        const rows = t.numRows === null ? 'n/d (mai analizzata)' : t.numRows + ' righe (stats)';
        div.innerHTML = `<div class="tn">${esc(t.tableName)}</div><div class="meta">${rows} · ${esc(t.sizeMb)} MB</div>`;
        div.addEventListener('click', () => selectTable(t.tableName));
        tableList.appendChild(div);
    });
    if (selectedTable) [...tableList.children].forEach(c => c.classList.toggle('active', c.dataset.name === selectedTable));
}

async function selectTable(name) {
    selectedTable = name;
    [...tableList.children].forEach(c => c.classList.toggle('active', c.dataset.name === name));
    tableDetail.innerHTML = '<p class="muted">Caricamento…</p>';
    const res = await fetch('/api/schema/tables/' + encodeURIComponent(name));
    const d = await res.json();
    if (!res.ok) { tableDetail.innerHTML = `<p class="muted">Errore: ${esc(d.error)}</p>`; return; }
    renderDetail(d);
}

function renderDetail(d) {
    const stat = (k, v) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`;
    const numRows = (d.numRows === null || d.numRows === undefined) ? 'n/d' : d.numRows;
    const meta = showColMeta.checked;
    const colHead = meta
        ? '<tr><th class="num">#</th><th>Colonna</th><th>Tipo</th><th>Nullable</th><th>Chiave</th></tr>'
        : '<tr><th class="num">#</th><th>Colonna</th></tr>';
    const colRows = d.columns.map(c => meta
        ? `<tr><td class="num">${c.columnId}</td><td class="mono">${esc(c.columnName)}</td>
             <td class="mono">${esc(c.type)}</td><td>${c.nullable ? 'sì' : 'NO'}</td>
             <td>${c.primaryKey ? '<span class="pill pk">PK</span>' : ''}</td></tr>`
        : `<tr><td class="num">${c.columnId}</td><td class="mono">${esc(c.columnName)}</td></tr>`).join('');
    const idxRows = (d.indexes || []).map(ix =>
        `<tr><td class="mono">${esc(ix.indexName)}</td><td class="mono">${esc(ix.columns)}</td>
             <td>${esc(ix.uniqueness)}</td><td>${esc(ix.status)}</td>
             <td><button class="small dropIdx" data-idx="${esc(ix.indexName)}">Elimina</button></td></tr>`
    ).join('') || '<tr><td colspan="5" class="muted">Nessun indice.</td></tr>';
    const colOptions = d.columns.map(c => `<option value="${esc(c.columnName)}">${esc(c.columnName)}</option>`).join('');

    tableDetail.innerHTML = `
        <h2>${esc(d.tableName)}</h2>
        <div class="statgrid">
            ${stat('Righe (stats)', numRows)}${stat('Dimensione', esc(d.sizeMb) + ' MB')}
            ${stat('Blocchi', d.blocks ?? 'n/d')}${stat('Avg row len', (d.avgRowLen ?? 'n/d') + ' B')}
            ${stat('Ultima analisi', d.lastAnalyzed ? esc(d.lastAnalyzed) : 'mai')}
        </div>
        <div class="controls" style="margin-bottom:6px">
            <button id="exactCount">Conteggio esatto COUNT(*)</button>
            <button id="gatherStats">Aggiorna statistiche</button>
            <span id="exactCountOut" class="note"></span>
        </div>
        <p class="note">Le "righe (stats)" vengono dalle statistiche dell'ottimizzatore e possono
           essere stantie: confrontale con il conteggio esatto.</p>

        <h2>Colonne (${d.columns.length})</h2>
        <div class="controls" style="margin-bottom:6px">
            <label>Crea indice su: <select id="idxCol">${colOptions}</select></label>
            <button id="createIdx">Crea indice</button>
            <span id="idxOut" class="note"></span>
        </div>
        <div class="scroll"><table><thead>${colHead}</thead><tbody>${colRows}</tbody></table></div>

        <h2>Indici (${(d.indexes || []).length})</h2>
        <div class="scroll"><table>
            <thead><tr><th>Indice</th><th>Colonne</th><th>Univocità</th><th>Stato</th><th></th></tr></thead>
            <tbody>${idxRows}</tbody></table></div>`;

    document.getElementById('exactCount').addEventListener('click', async () => {
        const out = document.getElementById('exactCountOut');
        out.textContent = 'Conteggio…';
        const r = await fetch(`/api/schema/tables/${encodeURIComponent(d.tableName)}/count`);
        const j = await r.json();
        out.textContent = r.ok ? `${j.count} righe reali — ${fmt(j.elapsedMs)} ms` : `Errore: ${j.error}`;
    });
    document.getElementById('gatherStats').addEventListener('click', async () => {
        const out = document.getElementById('exactCountOut');
        out.textContent = 'DBMS_STATS…';
        const r = await fetch(`/api/admin/gather-stats?table=${encodeURIComponent(d.tableName)}`, { method: 'POST' });
        const j = await r.json();
        out.textContent = r.ok ? `Statistiche aggiornate — ${fmt(j.elapsedMs)} ms` : `Errore: ${j.error}`;
        if (r.ok) selectTable(d.tableName);
    });
    document.getElementById('createIdx').addEventListener('click', async () => {
        const col = document.getElementById('idxCol').value;
        const out = document.getElementById('idxOut');
        out.textContent = 'Creazione…';
        const r = await fetch(`/api/admin/index?table=${encodeURIComponent(d.tableName)}&column=${encodeURIComponent(col)}`, { method: 'POST' });
        const j = await r.json();
        out.textContent = r.ok ? `Creato ${j.indexName} — ${fmt(j.elapsedMs)} ms` : `Errore: ${j.error}`;
        if (r.ok) selectTable(d.tableName);
    });
    tableDetail.querySelectorAll('.dropIdx').forEach(btn => btn.addEventListener('click', async () => {
        const name = btn.dataset.idx;
        const r = await fetch(`/api/admin/index/${encodeURIComponent(name)}`, { method: 'DELETE' });
        const j = await r.json();
        if (r.ok) selectTable(d.tableName); else alert('Errore: ' + j.error);
    }));
}

/** Lazy load on first activation of the Tabelle tab. */
export function load() {
    loadTables();
    loadDatasets();
}

/** Wires the Tabelle tab controls. */
export function init() {
    document.getElementById('reloadTables').addEventListener('click', () => { loadTables(); loadDatasets(); });
    showColMeta.addEventListener('change', () => { if (selectedTable) selectTable(selectedTable); });
    document.getElementById('seedBtn').addEventListener('click', async () => {
        const n = document.getElementById('seedN').value;
        seedOut.textContent = `Inserimento di ${n} righe…`;
        const r = await fetch(`/api/admin/seed?n=${encodeURIComponent(n)}`, { method: 'POST' });
        const j = await r.json();
        seedOut.textContent = r.ok ? `Inserite ${j.inserted} righe in ${fmt(j.elapsedMs)} ms. Ricorda di aggiornare le statistiche.` : `Errore: ${j.error}`;
        if (r.ok) loadTables();
    });
    document.getElementById('truncateBtn').addEventListener('click', async () => {
        if (!confirm('Svuotare bench_customers?')) return;
        const r = await fetch('/api/admin/truncate', { method: 'POST' });
        const j = await r.json();
        seedOut.textContent = r.ok ? `Tabella svuotata (${fmt(j.elapsedMs)} ms).` : `Errore: ${j.error}`;
        if (r.ok) loadTables();
    });
}
