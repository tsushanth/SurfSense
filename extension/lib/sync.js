// Flushes dirty days to the backend. Each day's total is cumulative and REPLACES the server's value,
// so a retry or a double flush is harmless. Never throws.

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
          const c = categories && typeof categories[d] === 'string' && categories[d] ? categories[d] : 'Other';
          summary[c] = (summary[c] || 0) + seconds[d];
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
