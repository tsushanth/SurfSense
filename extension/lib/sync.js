// Flushes dirty days to the backend. Each day's total is cumulative and REPLACES the server's value,
// so a retry or a double flush is harmless. Never throws.

// Own-key accumulation: a category named `constructor` or `__proto__` must stay a plain number.
function addOwn(obj, key, n) {
  const prev = Object.hasOwn(obj, key) ? obj[key] : 0;
  Object.defineProperty(obj, key, { value: prev + n, writable: true, enumerable: true, configurable: true });
}

export async function flushDirty({ api, tracker }) {
  const flushed = [];
  const failed = [];
  let snap;
  try {
    await tracker.tick();
    snap = await tracker.snapshot();
  } catch {
    return { flushed, failed };
  }
  for (const day of Object.keys(snap.dirty).sort()) {
    const rev = snap.dirty[day];
    try {
      const seconds = snap.days[day] || {};
      const domains = Object.keys(seconds);
      if (domains.length > 0) {
        const categories = await api.categorize(domains);
        const summary = {};
        for (const d of domains) {
          const raw = categories && Object.hasOwn(categories, d) ? categories[d] : undefined;
          const c = typeof raw === 'string' && raw ? raw : 'Other';
          addOwn(summary, c, seconds[d]);
        }
        await api.submitSummary(day, summary);
      }
      await tracker.markFlushed(day, rev);
      flushed.push(day);
    } catch {
      failed.push(day);
    }
  }
  return { flushed, failed };
}
