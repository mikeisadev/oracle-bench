// Tab switching. Feature modules stay decoupled: on activation we just call the
// `onShow(tabName)` callback provided by main.js, which handles lazy loading.

/** Programmatically activates a tab by its data-tab name. */
export function openTab(name) {
    document.querySelector(`.tab[data-tab="${name}"]`).click();
}

/** Wires the tab bar; `onShow` is invoked with the activated tab's name. */
export function initTabs(onShow) {
    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
            tab.classList.add('active');
            document.getElementById('panel-' + tab.dataset.tab).classList.add('active');
            if (onShow) onShow(tab.dataset.tab);
        });
    });
}
