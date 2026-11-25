import { describe, it, expect } from 'vitest';
import { normalizeClientType } from '../clientType.js';

describe('client type', () => {
    it('keeps the known types and maps the old Android name', () => {
        expect(normalizeClientType('ANDROID')).toBe('ANDROID');
        expect(normalizeClientType('CHROME_EXTENSION')).toBe('CHROME_EXTENSION');
        expect(normalizeClientType('ANDROID_PHONE')).toBe('ANDROID');
    });
    it('stores anything else as UNKNOWN', () => {
        for (const t of [undefined, null, '', 'WINDOWS', 'android']) expect(normalizeClientType(t)).toBe('UNKNOWN');
    });
});
