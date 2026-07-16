// Shared formatting/escaping helpers used across all modules.

/** Formats a number to `d` decimals, or an em-dash for null/undefined/''. */
export const fmt = (n, d = 3) => (n === null || n === undefined || n === '') ? '—' : Number(n).toFixed(d);

/** Escapes &, <, > for safe insertion into innerHTML. */
export const esc = s => String(s ?? '').replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
