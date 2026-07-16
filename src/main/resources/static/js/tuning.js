// SQL Tuning tab: execution plan (Explain / Autotrace) for a predefined query,
// cumulative V$SQL stats by sql_id, and the anti-pattern catalog (bad vs good).

import { fmt, esc } from './util.js';

const tuningSelect = document.getElementById('tuningSelect');
const tParam = document.getElementById('tParam');
const tuningPlan = document.getElementById('tuningPlan');
const autotraceStats = document.getElementById('autotraceStats');

function tuningUrl(kind) {
    const p = tParam.value;
    let url = `/api/tuning/${kind}?id=${encodeURIComponent(tuningSelect.value)}`;
    if (p !== '') url += `&p=${encodeURIComponent(p)}`;
    return url;
}

async function runExplain() {
    autotraceStats.classList.add('hidden');
    tuningPlan.textContent = 'Explain plan…';
    const r = await fetch(tuningUrl('plan'));
    const j = await r.json();
    tuningPlan.textContent = r.ok ? (j.plan || []).join('\n') : 'Errore: ' + j.error;
}

async function runAutotrace() {
    tuningPlan.textContent = 'Autotrace…';
    autotraceStats.classList.add('hidden');
    const r = await fetch(tuningUrl('autotrace'));
    const j = await r.json();
    if (!r.ok) { tuningPlan.textContent = 'Errore: ' + j.error; return; }
    renderAutotraceStats(j);
    tuningPlan.textContent = (j.plan || []).join('\n');
}

function renderAutotraceStats(j) {
    const stat = (k, v) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`;
    const s = j.stats || {};
    autotraceStats.innerHTML =
        stat('Righe', j.rows) + stat('Tempo', fmt(j.elapsedMs) + ' ms') +
        stat('sql_id', `<span class="mono">${esc(j.sqlId)}</span>`) +
        stat('Logical reads', s['session logical reads'] ?? '—') +
        stat('Consistent gets', s['consistent gets'] ?? '—') +
        stat('Physical reads', s['physical reads'] ?? '—') +
        stat('Sorts (mem)', s['sorts (memory)'] ?? '—');
    autotraceStats.classList.remove('hidden');
    if (j.sqlId) document.getElementById('sqlIdInput').value = j.sqlId;
}

async function loadAntiPatterns() {
    const box = document.getElementById('antiPatterns');
    const r = await fetch('/api/tuning/antipatterns');
    const list = await r.json();
    if (!r.ok) { box.innerHTML = `<p class="note">Errore: ${esc(list.error)}</p>`; return; }
    box.innerHTML = list.map(ap => `
        <div class="card" data-id="${esc(ap.id)}">
            <h3>${esc(ap.title)}</h3>
            <p class="note">${esc(ap.problem)}</p>
            <p class="note"><strong>Come si risolve:</strong> ${esc(ap.fix)}
               ${ap.needsIndexOn ? ` <span class="pill">richiede indice su ${esc(ap.needsIndexOn)}</span>` : ''}</p>
            <div class="cols2">
                <div>
                    <div class="bad mono" style="font-size:0.8rem">${esc(ap.badSql)}</div>
                    <div class="controls" style="margin:6px 0">
                        <button class="small ap" data-variant="bad" data-kind="plan">Explain</button>
                        <button class="small ap" data-variant="bad" data-kind="autotrace">Autotrace</button>
                    </div>
                    <pre class="plan apOut" data-variant="bad">—</pre>
                </div>
                <div>
                    <div class="good mono" style="font-size:0.8rem">${esc(ap.goodSql)}</div>
                    <div class="controls" style="margin:6px 0">
                        <button class="small ap" data-variant="good" data-kind="plan">Explain</button>
                        <button class="small ap" data-variant="good" data-kind="autotrace">Autotrace</button>
                    </div>
                    <pre class="plan apOut" data-variant="good">—</pre>
                </div>
            </div>
        </div>`).join('');

    box.querySelectorAll('button.ap').forEach(btn => btn.addEventListener('click', async () => {
        const card = btn.closest('.card');
        const id = card.dataset.id, variant = btn.dataset.variant, kind = btn.dataset.kind;
        const pre = card.querySelector(`.apOut[data-variant="${variant}"]`);
        pre.textContent = '…';
        const r = await fetch(`/api/tuning/antipatterns/${encodeURIComponent(id)}/${kind}?variant=${variant}`);
        const j = await r.json();
        if (!r.ok) { pre.textContent = 'Errore: ' + j.error; return; }
        let head = '';
        if (kind === 'autotrace') {
            const s = j.stats || {};
            head = `-- ${j.rows} righe, ${fmt(j.elapsedMs)} ms, logical reads=${s['session logical reads'] ?? '?'}\n`;
        }
        pre.textContent = head + (j.plan || []).join('\n');
    }));
}

/** Lazy load on first activation of the SQL Tuning tab. */
export const load = loadAntiPatterns;

/** Wires the SQL Tuning tab controls. */
export function init() {
    document.getElementById('btnExplain').addEventListener('click', runExplain);
    document.getElementById('btnAutotrace').addEventListener('click', runAutotrace);
    document.getElementById('btnSqlStats').addEventListener('click', async () => {
        const id = document.getElementById('sqlIdInput').value.trim();
        const out = document.getElementById('sqlStatsOut');
        if (!id) { out.innerHTML = '<p class="note">Inserisci un sql_id.</p>'; return; }
        const r = await fetch('/api/tuning/sqlstats/' + encodeURIComponent(id));
        const j = await r.json();
        if (!r.ok) { out.innerHTML = `<p class="note">Errore: ${esc(j.error)}</p>`; return; }
        out.innerHTML = '<table><tbody>' + Object.entries(j).map(([k, v]) =>
            `<tr><th>${esc(k)}</th><td class="num mono">${esc(v)}</td></tr>`).join('') + '</tbody></table>';
    });
}
