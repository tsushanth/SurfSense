import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { usageAccessReport, REFUSAL_REASONS } from '../usageAccessReport.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

describe('usage access refusal report', () => {
    it('keeps the reason, phone model, Android release and app version', () => {
        expect(usageAccessReport({ reason: 'not_now', device: 'Google Pixel 9', android: '15', appVersion: '10.2.2' })).toEqual({
            reason: 'not_now',
            context: { reason: 'not_now', device: 'Google Pixel 9', android: '15', appVersion: '10.2.2' },
        });
    });

    it('drops anything else the caller sends and bounds the text', () => {
        const report = usageAccessReport({ reason: 'settings_unavailable', clientId: 'abc', device: 'x'.repeat(500), android: 15 });
        expect(Object.keys(report.context).sort()).toEqual(['android', 'appVersion', 'device', 'reason']);
        expect(report.context.device).toHaveLength(80);
        expect(report.context.android).toBe('');
    });

    it('rejects unknown or missing reasons', () => {
        for (const body of [undefined, {}, { reason: 'because' }, { reason: ['not_now'] }]) expect(usageAccessReport(body)).toBeNull();
    });

    it('the route needs a client key and reports one stable flow', () => {
        const server = fs.readFileSync(path.join(root, 'server.js'), 'utf8');
        expect(server).toMatch(/app\.post\('\/usage-access-refused', authenticateClient, usageAccessLimiter/);
        expect(server).toMatch(/reportFailure\('usage_access_refused'/);
    });

    it('accepts every reason the Android app sends', () => {
        const kt = fs.readFileSync(
            path.join(root, '..', 'android', 'app', 'src', 'main', 'java', 'com', 'kreativekoala', 'surfsense', 'usage', 'UsageAccessReports.kt'),
            'utf8'
        );
        const sent = [...kt.matchAll(/^\s+[A-Z_]+\("([a-z_]+)"\)/gm)].map((m) => m[1]);
        expect(sent.sort()).toEqual([...REFUSAL_REASONS].sort());
    });
});
