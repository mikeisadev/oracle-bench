// Composition root: wire every module once, set up lazy loading per tab, then
// load the predefined queries and paint the initial Benchmark preview.

import { initTabs } from './tabs.js';
import { loadQueries } from './queries.js';
import * as benchmark from './benchmark.js';
import * as schema from './schema.js';
import * as custom from './custom.js';
import * as tuning from './tuning.js';
import { initGuide } from './guide.js';

benchmark.init();
schema.init();
custom.init();
tuning.init();
initGuide();

// Each tab fetches its data only the first time it is opened.
const loaded = { schema: false, custom: false, tuning: false };
initTabs(tab => {
    if (tab === 'schema' && !loaded.schema) { loaded.schema = true; schema.load(); }
    if (tab === 'custom' && !loaded.custom) { loaded.custom = true; custom.load(); }
    if (tab === 'tuning' && !loaded.tuning) { loaded.tuning = true; tuning.load(); }
});

loadQueries().then(() => benchmark.showPreview());
