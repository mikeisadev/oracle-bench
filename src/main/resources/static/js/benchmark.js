// Benchmark tab: run a predefined query once or 10x, accumulate timings
// (green = fastest, red = slowest) and optionally show the returned rows.

import { fmt, esc } from './util.js';
import { getQuerySql } from './queries.js';

const querySelect = document.getElementById('querySelect');
const paramInput = document.getElementById('param');
const includeData = document.getElementById('includeData');
const sqlPreview = document.getElementById('sqlPreview');
const resultsBody = document.getElementById('results');
const runOnce = document.getElementById('runOnce');
const runTen = document.getElementById('runTen');
const viewPerf = document.getElementById('viewPerf');
const viewData = document.getElementById('viewData');
const perfView = document.getElementById('perfView');
const dataView = document.getElementById('dataView');
let counter = 0;

/** Refreshes the SQL preview from the currently selected query. */
export const showPreview = () => sqlPreview.textContent = getQuerySql(querySelect.value) || '';

function setBusy(b) { runOnce.disabled = b; runTen.disabled = b; }

async function runQuery() {
    const id = querySelect.value, p = paramInput.value;
    let url = `/api/query?id=${encodeURIComponent(id)}`;
    if (p !== '') url += `&p=${encodeURIComponent(p)}`;
    if (includeData.checked) url += '&data=true';
    const res = await fetch(url);
    const data = await res.json();
    if (!res.ok) { addRow({ id, rows: '—', elapsedMs: null, sqlId: 'ERRORE: ' + (data.error || res.status) }); return; }
    addRow(data);
    if (includeData.checked) renderData(data);
}

function addRow(data) {
    const tr = document.createElement('tr');
    tr.dataset.ms = (data.elapsedMs ?? '') === '' ? '' : data.elapsedMs;
    tr.innerHTML = `<td class="num">${++counter}</td><td>#${esc(data.id)}</td>
        <td class="num">${esc(data.rows)}</td><td class="num ms">${fmt(data.elapsedMs)}</td>
        <td class="mono">${esc(data.sqlId || '')}</td>`;
    resultsBody.appendChild(tr);
    highlightExtremes();
}

function highlightExtremes() {
    const rows = [...resultsBody.querySelectorAll('tr')].filter(r => r.dataset.ms !== '');
    rows.forEach(r => r.classList.remove('min', 'max'));
    if (rows.length < 2) return;
    let min = rows[0], max = rows[0];
    rows.forEach(r => {
        const v = parseFloat(r.dataset.ms);
        if (v < parseFloat(min.dataset.ms)) min = r;
        if (v > parseFloat(max.dataset.ms)) max = r;
    });
    min.classList.add('min'); max.classList.add('max');
}

function renderData(data) {
    const cols = data.columns || [];
    document.getElementById('dataHead').innerHTML = '<tr>' + cols.map(c => `<th>${esc(c)}</th>`).join('') + '</tr>';
    document.getElementById('dataBody').innerHTML = (data.data || []).map(row =>
        '<tr>' + cols.map(c => `<td class="mono">${esc(row[c])}</td>`).join('') + '</tr>').join('');
    document.getElementById('dataNote').textContent = `Query #${data.id}: ${data.rows} righe`
        + (data.truncated ? ` (mostrate le prime ${(data.data || []).length})` : '') + ` — ${fmt(data.elapsedMs)} ms`;
}

function switchView(toData) {
    viewData.classList.toggle('active', toData);
    viewPerf.classList.toggle('active', !toData);
    dataView.classList.toggle('hidden', !toData);
    perfView.classList.toggle('hidden', toData);
    if (toData && !includeData.checked) {
        document.getElementById('dataNote').textContent = 'Attiva "Includi dati" e riesegui una query.';
        document.getElementById('dataHead').innerHTML = '';
        document.getElementById('dataBody').innerHTML = '';
    }
}

async function runN(n) { setBusy(true); try { for (let i = 0; i < n; i++) await runQuery(); } finally { setBusy(false); } }

/** Wires the Benchmark tab controls. */
export function init() {
    querySelect.addEventListener('change', showPreview);
    runOnce.addEventListener('click', () => runN(1));
    runTen.addEventListener('click', () => runN(10));
    document.getElementById('clear').addEventListener('click', () => { resultsBody.innerHTML = ''; counter = 0; });
    viewPerf.addEventListener('click', () => switchView(false));
    viewData.addEventListener('click', () => switchView(true));
}
