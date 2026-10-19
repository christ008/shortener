import { describe, expect, it } from 'vitest';
import { messageFor } from './problem';

describe('messageFor', () => {
    it('says the code is taken on a 409, whatever the detail', () => {
        expect(messageFor(409, { title: 'Conflict', status: 409, detail: 'x' })).toMatch(
            /taken, or reserved/
        );
    });

    it('says when to try again on a 429 from Retry-After, in seconds', () => {
        expect(messageFor(429, undefined, '12')).toBe(
            'Too many requests. Try again in 12 seconds.'
        );
        expect(messageFor(429, undefined, null)).toBe('Too many requests. Try again in a moment.');
    });

    it('names what was refused on a 403, from the detail', () => {
        expect(
            messageFor(403, { title: 'Forbidden', status: 403, detail: 'needs shortlinks:admin' })
        ).toBe('needs shortlinks:admin');
    });

    it('asks to sign in again on a 401', () => {
        expect(messageFor(401, undefined)).toMatch(/Sign in again/);
    });

    it('falls back to the title of the problem for a status it does not name', () => {
        expect(messageFor(500, { title: 'Internal Server Error', status: 500 })).toBe(
            'Internal Server Error'
        );
    });
});
