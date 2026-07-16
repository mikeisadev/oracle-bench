// Loads the predefined queries once and populates both the Benchmark and the
// SQL Tuning selects. Keeps the id -> sql map for the SQL preview.

const queriesById = {};

/** Returns the SQL text of a predefined query id (or undefined). */
export function getQuerySql(id) {
    return queriesById[id];
}

/** Fetches /api/queries and fills #querySelect and #tuningSelect. */
export async function loadQueries() {
    const list = await (await fetch('/api/queries')).json();
    const selects = [document.getElementById('querySelect'), document.getElementById('tuningSelect')];
    selects.forEach(sel => sel.innerHTML = '');
    list.forEach(q => {
        queriesById[q.id] = q.sql;
        selects.forEach(sel => {
            const opt = document.createElement('option');
            opt.value = q.id;
            opt.textContent = `#${q.id} — ${q.sql.split('\n')[0].slice(0, 55)}`;
            sel.appendChild(opt);
        });
    });
}
