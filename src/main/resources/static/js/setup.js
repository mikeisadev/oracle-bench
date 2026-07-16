// One-click database setup: a confirm dialog, a pre-flight check that the Docker
// container is running, then a live xterm.js terminal fed by the /api/setup/run
// Server-Sent Events stream (each SSE line = one terminal line, ANSI-coloured).
// The stream ends with a `done` event so EventSource does not auto-reconnect.

let term = null;
let fitAddon = null;
let source = null;
let finished = false;

function show(id) { document.getElementById(id).classList.add('open'); }
function hide(id) { document.getElementById(id).classList.remove('open'); }

// ---------- pre-flight: is the container up? ----------

async function onConfirm() {
    const btn = document.getElementById('setupConfirm');
    btn.disabled = true;
    try {
        const st = await (await fetch('/api/setup/status')).json();
        if (st.dockerAvailable && st.containerRunning) {
            hide('setupOverlay');
            startSetup();
        } else {
            hide('setupOverlay');
            openError(st);
        }
    } catch (e) {
        hide('setupOverlay');
        openError({ dockerAvailable: false, containerRunning: false });
    } finally {
        btn.disabled = false;
    }
}

function openError(st) {
    const msg = !st.dockerAvailable
        ? 'Docker non è raggiungibile dal processo dell’app (Docker Desktop spento, o non nel PATH). '
          + 'Avvia l’app da un terminale in cui il comando "docker" funziona.'
        : `Il container "${st.container || 'oracle-corso'}" non è in esecuzione. `
          + 'Avvialo (vedi sotto), poi riprova.';
    document.getElementById('setupErrMsg').textContent = msg;
    show('setupErrOverlay');
}

async function onRetry() {
    const btn = document.getElementById('setupErrRetry');
    btn.disabled = true;
    try {
        const st = await (await fetch('/api/setup/status')).json();
        if (st.dockerAvailable && st.containerRunning) {
            hide('setupErrOverlay');
            startSetup();
        } else {
            openError(st);
        }
    } finally {
        btn.disabled = false;
    }
}

// ---------- terminal + SSE ----------

function ensureTerm() {
    if (term) { term.clear(); return term; }
    term = new window.Terminal({
        convertEol: true, scrollback: 5000,
        fontSize: 13, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
        theme: { background: '#000000', foreground: '#e5e7eb' }
    });
    if (window.FitAddon) {
        fitAddon = new window.FitAddon.FitAddon();
        term.loadAddon(fitAddon);
    }
    term.open(document.getElementById('termHost'));
    return term;
}

function fitTerm() {
    if (fitAddon) {
        try { fitAddon.fit(); } catch (e) { /* container not laid out yet */ }
    }
}

function startSetup() {
    finished = false;
    document.getElementById('termStatus').textContent = 'Esecuzione in corso… (non chiudere finché non termina)';
    show('termOverlay');
    const t = ensureTerm();
    // Let the modal lay out before measuring, so the terminal fills the width.
    setTimeout(fitTerm, 60);
    t.writeln('[2mConnessione allo stream di setup…[0m');

    source = new EventSource('/api/setup/run');
    source.onmessage = e => t.writeln(e.data);
    source.addEventListener('done', e => {
        finished = true;
        closeSource();
        document.getElementById('termStatus').textContent = e.data === 'ok'
            ? '✔ Setup completato con successo.'
            : (e.data === 'busy' ? 'Un setup è già in corso.' : '✗ Setup terminato con errori — controlla l’output.');
    });
    source.onerror = () => {
        if (finished) return;
        document.getElementById('termStatus').textContent = 'Connessione interrotta.';
        closeSource();
    };
}

function closeSource() {
    if (source) { source.close(); source = null; }
}

function closeTerm() {
    closeSource();
    // reset fullscreen so next open starts windowed
    document.getElementById('termModal').classList.remove('fullscreen');
    document.getElementById('termOverlay').classList.remove('no-pad');
    document.getElementById('termFull').textContent = '⛶ Schermo intero';
    hide('termOverlay');
}

function toggleFullscreen() {
    const modal = document.getElementById('termModal');
    const full = modal.classList.toggle('fullscreen');
    document.getElementById('termOverlay').classList.toggle('no-pad', full);
    document.getElementById('termFull').textContent = full ? '⤡ Riduci' : '⛶ Schermo intero';
    setTimeout(fitTerm, 60);
}

/** Wires the "Avvia setup" button, the confirm/error dialogs and the terminal. */
export function initSetup() {
    document.getElementById('setupBtn').addEventListener('click', () => show('setupOverlay'));
    document.getElementById('setupConfirm').addEventListener('click', onConfirm);
    document.getElementById('setupCancel').addEventListener('click', () => hide('setupOverlay'));
    document.getElementById('setupCancel2').addEventListener('click', () => hide('setupOverlay'));
    document.getElementById('setupOverlay').addEventListener('click', e => { if (e.target.id === 'setupOverlay') hide('setupOverlay'); });

    document.getElementById('setupErrRetry').addEventListener('click', onRetry);
    document.getElementById('setupErrClose').addEventListener('click', () => hide('setupErrOverlay'));
    document.getElementById('setupErrClose2').addEventListener('click', () => hide('setupErrOverlay'));

    document.getElementById('termClose').addEventListener('click', closeTerm);
    document.getElementById('termFull').addEventListener('click', toggleFullscreen);
    window.addEventListener('resize', () => { if (document.getElementById('termOverlay').classList.contains('open')) fitTerm(); });
}
