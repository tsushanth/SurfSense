import { test } from 'node:test';
import assert from 'node:assert/strict';
import { shouldStopPolling } from '../lib/linkpoll.js';

test('polling stops only when the linked-device count grows', () => {
  assert.equal(shouldStopPolling(0, 0), false);
  assert.equal(shouldStopPolling(1, 1), false);
  assert.equal(shouldStopPolling(1, 2), true);
  assert.equal(shouldStopPolling(0, 1), true);
  assert.equal(shouldStopPolling(2, 1), false);
});
