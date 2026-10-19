import { describe, expect, it } from 'vitest';
import { authorizedFetch, NotSignedInError, restoreSession, sessionStore } from './session';

describe('the session', () => {
    it('has no session in a browser that never signed in', async () => {
        expect(sessionStore.getSnapshot()).toBeNull();
        expect(await restoreSession()).toBeNull();
    });

    it('refuses to sign a request when nobody is signed in, and sends nothing', async () => {
        await expect(
            authorizedFetch(new Request('http://localhost/api/short-links'))
        ).rejects.toBeInstanceOf(NotSignedInError);
    });
});
