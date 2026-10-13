import { describe, it, expect } from 'vitest';
import { candidateDomains, createLabeledLookup, lookupLabeled } from '../domainList.js';

describe('candidateDomains', () => {
    it('lowercases and returns the host then its parents down to two labels', () => {
        expect(candidateDomains('WWW.Docs.Example.co.uk')).toEqual([
            'www.docs.example.co.uk', 'docs.example.co.uk', 'example.co.uk', 'co.uk',
        ]);
    });

    it('strips protocol, port, path, query, fragment and trailing dot', () => {
        expect(candidateDomains('https://foo.com:8080/a/b?x=1#y')).toEqual(['foo.com']);
        expect(candidateDomains('foo.com.')).toEqual(['foo.com']);
    });

    it('returns [] for empty, whitespace, non-string, single label, IPs and malformed hosts', () => {
        for (const bad of ['', '   ', null, undefined, 42, 'localhost', '1.2.3.4', 'a..com', '../etc', 'bad host.com', 'a_b.com', '/']) {
            expect(candidateDomains(bad)).toEqual([]);
        }
    });
});

describe('createLabeledLookup', () => {
    const lookup = createLabeledLookup({ 'example.com': 'News', 'docs.example.com': 'Work/Productivity' });

    it('matches the exact host first, then parents', () => {
        expect(lookup('docs.example.com')).toBe('Work/Productivity');
        expect(lookup('www.example.com')).toBe('News');
        expect(lookup('a.b.example.com')).toBe('News');
    });

    it('returns null when nothing matches or input is bad', () => {
        expect(lookup('unknown.org')).toBeNull();
        expect(lookup('')).toBeNull();
    });
});

describe('lookupLabeled (shipped data file)', () => {
    it('loads the committed list and finds a well-known domain', () => {
        expect(lookupLabeled('www.espn.com')).toBe('Entertainment');
    });
});
