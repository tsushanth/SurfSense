import { test } from 'node:test';
import assert from 'node:assert/strict';
import { localDate, startOfNextLocalDay, chunk } from '../lib/dates.js';

const pad = (n) => String(n).padStart(2, '0');
const expectedLocal = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

test('localDate uses local calendar fields, not UTC', () => {
  const d = new Date(2026, 9, 12, 23, 30);
  assert.equal(localDate(d.getTime()), expectedLocal(d));
  assert.equal(localDate(d.getTime()), '2026-10-12');
});

test('localDate zero-pads month and day', () => {
  assert.equal(localDate(new Date(2026, 0, 5, 0, 0, 1).getTime()), '2026-01-05');
});

test('startOfNextLocalDay returns the next local midnight', () => {
  const d = new Date(2026, 9, 12, 23, 30, 15, 500);
  const next = new Date(startOfNextLocalDay(d.getTime()));
  assert.equal(next.getHours(), 0);
  assert.equal(next.getMinutes(), 0);
  assert.equal(next.getSeconds(), 0);
  assert.equal(next.getMilliseconds(), 0);
  assert.equal(next.getDate(), 13);
  assert.ok(next.getTime() > d.getTime());
});

test('startOfNextLocalDay is strictly greater when input is exactly midnight', () => {
  const mid = new Date(2026, 9, 12).getTime();
  const next = startOfNextLocalDay(mid);
  assert.ok(next > mid);
  assert.equal(new Date(next).getDate(), 13);
});

test('startOfNextLocalDay crosses month and year ends', () => {
  const eom = new Date(2026, 0, 31, 12).getTime();
  const n1 = new Date(startOfNextLocalDay(eom));
  assert.equal(n1.getMonth(), 1);
  assert.equal(n1.getDate(), 1);
  const eoy = new Date(2026, 11, 31, 23, 59).getTime();
  const n2 = new Date(startOfNextLocalDay(eoy));
  assert.equal(n2.getFullYear(), 2027);
  assert.equal(n2.getMonth(), 0);
  assert.equal(n2.getDate(), 1);
});

test('startOfNextLocalDay always lands on local midnight of the following date across a year of days (DST safe)', () => {
  for (let day = 0; day < 366; day++) {
    const d = new Date(2026, 0, 1 + day, 12);
    const next = new Date(startOfNextLocalDay(d.getTime()));
    const want = new Date(2026, 0, 2 + day);
    assert.equal(next.getTime(), want.getTime());
    assert.equal(next.getHours(), 0);
    assert.equal(next.getDate(), want.getDate());
  }
});

test('startOfNextLocalDay from just before midnight on every day of a year hits the next date', () => {
  for (let day = 0; day < 366; day++) {
    const d = new Date(2026, 0, 1 + day, 23, 59, 59, 999);
    const next = new Date(startOfNextLocalDay(d.getTime()));
    assert.equal(next.getHours(), 0);
    assert.equal(next.getTime() - d.getTime() <= 1, true);
  }
});

test('chunk splits 250 into 100/100/50', () => {
  const items = Array.from({ length: 250 }, (_, i) => i);
  const out = chunk(items, 100);
  assert.deepEqual(out.map((c) => c.length), [100, 100, 50]);
  assert.deepEqual(out.flat(), items);
});

test('chunk handles empty and exact multiples', () => {
  assert.deepEqual(chunk([], 100), []);
  assert.deepEqual(chunk([1, 2, 3, 4], 2), [[1, 2], [3, 4]]);
});

test('chunk rejects a non-positive size', () => {
  assert.throws(() => chunk([1], 0), RangeError);
});
