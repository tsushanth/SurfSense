const BASE = process.env.SMOKE_BASE_URL || 'https://usage-tracker-backend.fly.dev';
const stamp = Date.now();
const ids = { a: `smoke-a-${stamp}`, b: `smoke-b-${stamp}`, c: `smoke-c-${stamp}` };
const keys = {};
let failed = 0;

async function call(method, path, { key, body } = {}) {
    const res = await fetch(BASE + path, {
        method,
        headers: {
            'Content-Type': 'application/json',
            ...(key ? { Authorization: `Bearer ${key}` } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(30000),
    });
    let json = null;
    try { json = await res.json(); } catch { /* non-JSON body */ }
    return { status: res.status, json };
}

function check(name, cond, detail = '') {
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : '  ' + detail}`);
    if (!cond) failed++;
}

// Cold start: the machine may be stopped. Retry /health for up to ~60s.
const t0 = Date.now();
let health;
for (let i = 0; i < 6; i++) {
    try { health = await call('GET', '/health'); if (health.status === 200) break; } catch { /* retry */ }
    if (i < 5) await new Promise(r => setTimeout(r, 5000));
}
check('health (cold start ok)', health?.status === 200 && health.json?.db === 'ok', JSON.stringify(health));
console.log(`      first successful /health after ${Date.now() - t0} ms`);

if (process.env.SMOKE_HEALTH_ONLY === '1') {
    console.log(failed === 0 ? 'HEALTH ONLY: PASS' : 'HEALTH ONLY: FAIL');
    process.exit(failed === 0 ? 0 : 1);
}

for (const k of ['a', 'b', 'c']) {
    const r = await call('POST', '/register-client', { body: { clientId: ids[k], clientType: 'UNKNOWN', clientName: 'smoke-test' } });
    keys[k] = r.json?.apiKey;
    check(`register ${k}`, r.status === 201 && !!keys[k], JSON.stringify(r));
}
const again = await call('POST', '/register-client', { body: { clientId: ids.a, clientName: 'smoke-test' } });
check('re-register returns existing, no new key', again.status === 200 && !again.json?.apiKey);

const init = await call('POST', '/initiate-linking', { key: keys.a, body: { clientId: ids.a } });
check('A gets 6-digit code', init.status === 200 && /^\d{6}$/.test(init.json?.code || ''), JSON.stringify(init));

const stolen = await call('POST', '/complete-linking', { key: keys.c, body: { clientId: ids.b, code: init.json?.code } });
check('C cannot complete linking as B (403)', stolen.status === 403, JSON.stringify(stolen));

const badCode = await call('POST', '/complete-linking', { key: keys.c, body: { clientId: ids.c, code: '000000' } });
check('wrong linking code -> 400 with message (not 500)', badCode.status === 400 && badCode.json?.error === 'Invalid or expired code', JSON.stringify(badCode));

const done = await call('POST', '/complete-linking', { key: keys.b, body: { clientId: ids.b, code: init.json?.code } });
check('B completes linking with A', done.status === 200 && done.json?.success === true && done.json?.linkedWith === ids.a, JSON.stringify(done));

const links = await call('GET', `/get-linked-clients?clientId=${ids.a}`, { key: keys.a });
check('A sees B linked', links.json?.data?.clients?.some(c => c.clientId === ids.b), JSON.stringify(links));

const otherLinks = await call('GET', `/get-linked-clients?clientId=${ids.a}`, { key: keys.c });
check('C cannot list A\'s links (403)', otherLinks.status === 403, JSON.stringify(otherLinks));

const now = new Date();
const day = now.toISOString().split('T')[0];
const submit = await call('POST', '/submit-category-summary', { key: keys.b, body: { userId: ids.b, timestamp: now.toISOString(), categorySummary: { 'Social Media': 120, News: 45 } } });
check('B submits summary', submit.status === 200 && submit.json?.status === 'success', JSON.stringify(submit));

const own = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.b });
check('B reads own summary', own.status === 200 && own.json?.[0]?.summary?.['Social Media'] === 120, JSON.stringify(own));

const linked = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.a });
check('A (linked) reads B\'s summary', linked.status === 200 && linked.json?.length === 1, JSON.stringify(linked));

const stranger = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.c });
check('C (unlinked) cannot read B\'s summary (403)', stranger.status === 403, JSON.stringify(stranger));

const strangerUnlink = await call('POST', '/unlink-device', { key: keys.c, body: { clientIdA: ids.a, clientIdB: ids.b } });
check('C cannot unlink A and B (403)', strangerUnlink.status === 403, JSON.stringify(strangerUnlink));

const cats = await call('POST', '/get-category-mapping', { key: keys.a, body: { domains: ['facebook.com', 'WWW.BBC.COM', 'localhost', 'https://x.example/path'], packages: ['com.instagram.android', 'org.thoughtcrime.securesms', 'com.x/../y'] } });
check('categories: known domains', cats.json?.['facebook.com'] === 'Social Media' && cats.json?.['WWW.BBC.COM'] === 'News', JSON.stringify(cats));
check('categories: odd domains -> Other', cats.json?.['localhost'] === 'Other' && cats.json?.['https://x.example/path'] === 'Other', JSON.stringify(cats));
check('categories: Play package + hostile package', cats.json?.['com.instagram.android'] === 'Social Media' && cats.json?.['com.x/../y'] === 'Other', JSON.stringify(cats));
check('categories: Play-resolved package', cats.json?.['org.thoughtcrime.securesms'] === 'Social Media', JSON.stringify(cats));

const unlink = await call('POST', '/unlink-device', { key: keys.a, body: { clientIdA: ids.a, clientIdB: ids.b } });
check('A unlinks B', unlink.status === 200 && unlink.json?.success === true, JSON.stringify(unlink));

const after = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.a });
check('after unlink A can no longer read B (403)', after.status === 403, JSON.stringify(after));

console.log(failed === 0 ? '\nALL CHECKS PASSED' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
