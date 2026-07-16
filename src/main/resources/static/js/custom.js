// Query custom tab: table/column pickers, a read-only SELECT runner, plus
// Explain Plan / Autotrace on the free-hand SQL in the editor.

import { fmt, esc } from './util.js';
import { openGuide } from './guide.js';

const customTable = document.getElementById('customTable');
const colPick = document.getElementById('colPick');
const customSql = document.getElementById('customSql');

async function loadCustomTables() {
    const res = await fetch('/api/custom/tables');
    const data = await res.json();
    customTable.innerHTML = '<option value="">— scegli —</option>';
    if (!res.ok) { document.getElementById('customInfo').textContent = 'Errore: ' + data.error; return; }
    data.forEach(t => {
        const opt = document.createElement('option');
        opt.value = t.owner + '.' + t.table;
        opt.textContent = t.owner + '.' + t.table;
        customTable.appendChild(opt);
    });
}

async function loadColumns() {
    const val = customTable.value;
    if (!val) { colPick.classList.add('hidden'); return; }
    const [owner, table] = val.split('.');
    const res = await fetch(`/api/custom/columns?owner=${encodeURIComponent(owner)}&table=${encodeURIComponent(table)}`);
    const cols = await res.json();
    if (!res.ok) { document.getElementById('customInfo').textContent = 'Errore: ' + cols.error; return; }
    colPick.classList.remove('hidden');
    colPick.innerHTML = cols.map(c =>
        `<label><input type="checkbox" class="colcb" value="${esc(c.name)}" checked> ${esc(c.name)}
         <span class="muted" style="font-size:0.72rem">${esc(c.dataType)}</span></label>`).join('');
}

function selectedColumns() {
    const cbs = [...colPick.querySelectorAll('.colcb')];
    const checked = cbs.filter(c => c.checked).map(c => c.value);
    return (checked.length === 0 || checked.length === cbs.length) ? ['*'] : checked;
}

function buildSelect() {
    const val = customTable.value;
    if (!val) { customSql.value = ''; return; }
    const cols = selectedColumns().join(', ');
    customSql.value = `SELECT ${cols}\nFROM ${val}\nFETCH FIRST 50 ROWS ONLY`;
}

async function runCustom() {
    const info = document.getElementById('customInfo');
    info.textContent = 'Esecuzione…';
    const res = await fetch('/api/custom/run', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sql: customSql.value })
    });
    const j = await res.json();
    const head = document.getElementById('customHead'), body = document.getElementById('customBody');
    if (!res.ok) { info.textContent = 'Errore: ' + j.error; head.innerHTML = ''; body.innerHTML = ''; return; }
    const cols = j.columns || [];
    head.innerHTML = '<tr>' + cols.map(c => `<th>${esc(c)}</th>`).join('') + '</tr>';
    body.innerHTML = (j.data || []).map(row => '<tr>' + cols.map(c => `<td class="mono">${esc(row[c])}</td>`).join('') + '</tr>').join('');
    info.textContent = `${j.rows} righe${j.truncated ? ` (mostrate ${(j.data || []).length})` : ''} — ${fmt(j.elapsedMs)} ms`
        + (j.sqlId ? ` — sql_id ${j.sqlId}` : '');
}

async function customTune(kind) {
    const info = document.getElementById('customInfo');
    const pre = document.getElementById('customPlan');
    const stats = document.getElementById('customPlanStats');
    info.textContent = kind === 'plan' ? 'Explain plan…' : 'Autotrace…';
    stats.classList.add('hidden');
    const res = await fetch(`/api/tuning/${kind}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sql: customSql.value })
    });
    const j = await res.json();
    if (!res.ok) { info.textContent = 'Errore: ' + j.error; pre.classList.add('hidden'); return; }
    info.textContent = '';
    if (kind === 'autotrace') {
        const s = j.stats || {};
        const st = (k, v) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`;
        stats.innerHTML = st('Righe', j.rows) + st('Tempo', fmt(j.elapsedMs) + ' ms')
            + st('sql_id', `<span class="mono">${esc(j.sqlId)}</span>`)
            + st('Logical reads', s['session logical reads'] ?? '—')
            + st('Consistent gets', s['consistent gets'] ?? '—')
            + st('Physical reads', s['physical reads'] ?? '—');
        stats.classList.remove('hidden');
    }
    pre.textContent = (j.plan || []).join('\n');
    pre.classList.remove('hidden');
}

/** Lazy load on first activation of the Query custom tab. */
export const load = loadCustomTables;

/** Wires the Query custom tab controls. */
export function init() {
    customTable.addEventListener('change', loadColumns);
    document.getElementById('loadCols').addEventListener('click', loadColumns);
    document.getElementById('selectAllCols').addEventListener('click', () => {
        colPick.querySelectorAll('.colcb').forEach(c => c.checked = true);
    });
    document.getElementById('buildSelect').addEventListener('click', buildSelect);
    document.getElementById('runCustom').addEventListener('click', runCustom);
    document.getElementById('customExplain').addEventListener('click', () => customTune('plan'));
    document.getElementById('customAutotrace').addEventListener('click', () => customTune('autotrace'));
    document.getElementById('guideFromCustom').addEventListener('click', () => openGuide());
}
