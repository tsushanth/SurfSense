import { describe, it, expect, vi, afterEach } from 'vitest';
import { withDeadline } from '../deadline.js';

afterEach(() => vi.useRealTimers());

describe('withDeadline', () => {
    it('resolves to the value when the promise settles in time', async () => {
        vi.useFakeTimers();
        const p = withDeadline(Promise.resolve('ok'), 1000, 'fallback');
        await expect(p).resolves.toBe('ok');
        expect(vi.getTimerCount()).toBe(0);
    });

    it('resolves to the fallback when the deadline passes first', async () => {
        vi.useFakeTimers();
        const slow = new Promise(() => {});
        const p = withDeadline(slow, 1000, 'fallback');
        await vi.advanceTimersByTimeAsync(1000);
        await expect(p).resolves.toBe('fallback');
        expect(vi.getTimerCount()).toBe(0);
    });

    it('rejects if the promise rejects before the deadline and clears the timer', async () => {
        vi.useFakeTimers();
        const p = withDeadline(Promise.reject(new Error('boom')), 1000, 'fallback');
        await expect(p).rejects.toThrow('boom');
        expect(vi.getTimerCount()).toBe(0);
    });

    it('does not cancel the original promise', async () => {
        vi.useFakeTimers();
        let finished = false;
        const slow = new Promise(resolve => setTimeout(() => { finished = true; resolve('late'); }, 2000));
        const p = withDeadline(slow, 1000, 'fallback');
        await vi.advanceTimersByTimeAsync(1000);
        await expect(p).resolves.toBe('fallback');
        await vi.advanceTimersByTimeAsync(1000);
        expect(finished).toBe(true);
    });
});
