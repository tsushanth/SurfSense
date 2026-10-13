import { describe, it, expect } from 'vitest';
import { canReadSummary, isPartyToLink } from '../authz.js';

describe('canReadSummary', () => {
    it('allows reading your own summary', () => {
        expect(canReadSummary('me-12345', 'me-12345', [])).toBe(true);
    });

    it('allows reading a linked device', () => {
        expect(canReadSummary('me-12345', 'phone-123', ['phone-123', 'tab-1234'])).toBe(true);
    });

    it('denies reading an unlinked device', () => {
        expect(canReadSummary('me-12345', 'stranger-1', ['phone-123'])).toBe(false);
        expect(canReadSummary('me-12345', 'stranger-1', [])).toBe(false);
    });

    it('denies non-string or empty ids', () => {
        expect(canReadSummary('me-12345', '', [''])).toBe(false);
        expect(canReadSummary('me-12345', undefined, [])).toBe(false);
        expect(canReadSummary(undefined, undefined, [])).toBe(false);
    });
});

describe('isPartyToLink', () => {
    it('allows when the requester is either side', () => {
        expect(isPartyToLink('a-123456', 'a-123456', 'b-123456')).toBe(true);
        expect(isPartyToLink('b-123456', 'a-123456', 'b-123456')).toBe(true);
    });

    it('denies when the requester is neither side', () => {
        expect(isPartyToLink('c-123456', 'a-123456', 'b-123456')).toBe(false);
    });

    it('denies missing ids', () => {
        expect(isPartyToLink(undefined, undefined, undefined)).toBe(false);
        expect(isPartyToLink('a-123456', undefined, undefined)).toBe(false);
    });
});
