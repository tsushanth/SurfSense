import { describe, it, expect, vi } from 'vitest';
import { touchLastActive } from '../lastActive.js';

function fakeDb(result) {
    const calls = {};
    const eq = vi.fn(async (col, val) => {
        calls.eq = [col, val];
        if (result instanceof Error) throw result;
        return result;
    });
    const update = vi.fn((patch) => {
        calls.patch = patch;
        return { eq };
    });
    const from = vi.fn((table) => {
        calls.table = table;
        return { update };
    });
    return { db: { from }, calls };
}

describe('touchLastActive', () => {
    const fixed = () => new Date('2026-10-30T12:00:00.000Z');

    it('stamps last_active on the client row', async () => {
        const { db, calls } = fakeDb({ error: null });
        expect(await touchLastActive(db, 'client-1', null, fixed)).toBe(true);
        expect(calls.table).toBe('extension_clients');
        expect(calls.patch).toEqual({ last_active: '2026-10-30T12:00:00.000Z' });
        expect(calls.eq).toEqual(['client_id', 'client-1']);
    });

    it('returns false and logs when the update reports an error', async () => {
        const { db } = fakeDb({ error: { message: 'column does not exist' } });
        const log = { warn: vi.fn() };
        expect(await touchLastActive(db, 'client-1', log, fixed)).toBe(false);
        expect(log.warn).toHaveBeenCalledTimes(1);
    });

    it('never throws when the write throws', async () => {
        const { db } = fakeDb(new Error('network down'));
        await expect(touchLastActive(db, 'client-1', null, fixed)).resolves.toBe(false);
    });

    it('does nothing without a client id', async () => {
        const { db } = fakeDb({ error: null });
        expect(await touchLastActive(db, '', null, fixed)).toBe(false);
        expect(await touchLastActive(db, undefined, null, fixed)).toBe(false);
        expect(db.from).not.toHaveBeenCalled();
    });
});
