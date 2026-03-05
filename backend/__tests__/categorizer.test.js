import { describe, it, expect } from 'vitest';
import {
    categorizeLocally,
    normalizeDomain,
    matchByPattern,
    lookupStatic,
} from '../categorizer.js';

describe('normalizeDomain', () => {
    it('removes www. prefix', () => {
        expect(normalizeDomain('www.facebook.com')).toBe('facebook.com');
    });

    it('removes m. prefix', () => {
        expect(normalizeDomain('m.youtube.com')).toBe('youtube.com');
    });

    it('removes mobile. prefix', () => {
        expect(normalizeDomain('mobile.twitter.com')).toBe('twitter.com');
    });

    it('converts to lowercase', () => {
        expect(normalizeDomain('WWW.GITHUB.COM')).toBe('github.com');
    });

    it('trims whitespace', () => {
        expect(normalizeDomain('  facebook.com  ')).toBe('facebook.com');
    });

    it('returns null for null input', () => {
        expect(normalizeDomain(null)).toBeNull();
    });

    it('returns null for undefined input', () => {
        expect(normalizeDomain(undefined)).toBeNull();
    });
});

describe('lookupStatic - domains', () => {
    it('finds exact domain match', () => {
        expect(lookupStatic('facebook.com')).toBe('Social Media');
        expect(lookupStatic('youtube.com')).toBe('Entertainment');
        expect(lookupStatic('github.com')).toBe('Work/Productivity');
        expect(lookupStatic('amazon.com')).toBe('Shopping');
        expect(lookupStatic('coursera.org')).toBe('Education');
        expect(lookupStatic('cnn.com')).toBe('News');
        expect(lookupStatic('paypal.com')).toBe('Finance');
        expect(lookupStatic('webmd.com')).toBe('Health');
        expect(lookupStatic('booking.com')).toBe('Travel');
    });

    it('handles www. prefix', () => {
        expect(lookupStatic('www.facebook.com')).toBe('Social Media');
    });

    it('handles subdomain fallback', () => {
        expect(lookupStatic('mail.google.com')).toBe('Work/Productivity');
        expect(lookupStatic('docs.google.com')).toBe('Work/Productivity');
    });

    it('returns null for unknown domain', () => {
        expect(lookupStatic('randomsite12345.com')).toBeNull();
    });
});

describe('lookupStatic - Android packages', () => {
    it('finds exact package match', () => {
        expect(lookupStatic('com.facebook.katana', true)).toBe('Social Media');
        expect(lookupStatic('com.google.android.youtube', true)).toBe('Entertainment');
        expect(lookupStatic('com.amazon.mShop.android.shopping', true)).toBe('Shopping');
        expect(lookupStatic('com.dd.doordash', true)).toBe('Food');
    });

    it('returns null for unknown package', () => {
        expect(lookupStatic('com.unknown.app', true)).toBeNull();
    });
});

describe('matchByPattern', () => {
    it('matches social media patterns', () => {
        expect(matchByPattern('photos.instagram.com')).toBe('Social Media');
        expect(matchByPattern('api.twitter.com')).toBe('Social Media');
    });

    it('matches entertainment patterns', () => {
        expect(matchByPattern('music.spotify.com')).toBe('Entertainment');
        expect(matchByPattern('live.twitch.tv')).toBe('Entertainment');
    });

    it('matches work patterns', () => {
        expect(matchByPattern('app.slack.com')).toBe('Work/Productivity');
        expect(matchByPattern('mycompany.atlassian.net')).toBeNull(); // No jira/confluence in domain
    });

    it('matches shopping patterns', () => {
        expect(matchByPattern('shop.nike.com')).toBe('Shopping');
    });

    it('matches package patterns', () => {
        expect(matchByPattern('com.someapp.instagram.clone', true)).toBe('Social Media');
        expect(matchByPattern('com.myapp.music.player', true)).toBe('Entertainment');
    });

    it('returns null for unrecognized input', () => {
        expect(matchByPattern('completely-random-domain.io')).toBeNull();
    });
});

describe('categorizeLocally', () => {
    it('prefers static lookup over pattern matching', () => {
        // google.com is in static DB as Work/Productivity
        expect(categorizeLocally('google.com')).toBe('Work/Productivity');
    });

    it('falls back to pattern matching for non-static domains', () => {
        expect(categorizeLocally('my-facebook-clone.com')).toBe('Social Media');
    });

    it('categorizes Android packages', () => {
        expect(categorizeLocally('com.spotify.music', true)).toBe('Entertainment');
    });

    it('returns null for completely unknown inputs', () => {
        expect(categorizeLocally('xyz123abc.com')).toBeNull();
        expect(categorizeLocally('com.xyz123.abc', true)).toBeNull();
    });

    it('handles edge cases', () => {
        expect(categorizeLocally('')).toBeNull();
    });
});
