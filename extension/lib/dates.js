// Local-calendar date helpers. A "day" is the browser's local yyyy-MM-dd.

const pad = (n) => String(n).padStart(2, '0');

export function localDate(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// Epoch ms of the next local midnight. Built from calendar fields so a 23h or
// 25h DST day cannot skew it.
export function startOfNextLocalDay(ms) {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1, 0, 0, 0, 0).getTime();
}

export function chunk(items, size) {
  if (!Number.isInteger(size) || size < 1) throw new RangeError('chunk size must be a positive integer');
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}
