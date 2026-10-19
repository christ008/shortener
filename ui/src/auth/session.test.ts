// @vitest-environment node
import * as oauth from 'oauth4webapi';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, jwt, stubBrowser } from '../../test-utils/browser';
import { createKeyRecord, loadKeyRecord, saveKeyRecord } from './keys';
import {
    authorizedFetch,
    completeSignIn,
    NotSignedInError,
    restoreSession,
    sessionStore,
    signIn,
    signOut,
    SignInError,
} from './session';

const ISSUER = 'http://localhost:8180/realms/shortener';
const TOKEN = `${ISSUER}/protocol/openid-connect/token`;
const API = 'http://localhost:3000/api/short-links?page=0';
const API_ROOT = 'http://localhost:3000/api/short-links';
const ACTIVE = 'shortener-ui.active';

/** The address a request goes to, whether the app made it as a string, a URL or a Request. */
const addressOf = (input: Request | URL | string): string =>
    typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
const PENDING = 'shortener-ui.pending';

/** Answers the discovery document, the token endpoint with `tokens`, and anything else with `other`. */
function answer(fetch: ReturnType<typeof stubBrowser>['fetch'], tokens: () => Response) {
    fetch.mockImplementation(async (input) => {
        const url = addressOf(input);
        if (url === `${ISSUER}/.well-known/openid-configuration`) {
            return json({
                issuer: ISSUER,
                authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
                token_endpoint: TOKEN,
            });
        }
        if (url === TOKEN) {
            return tokens();
        }
        throw new Error(`unexpected request to ${url}`);
    });
}

/** The tokens of a sign-in or a refresh, bound to the browser key `jkt`. */
function tokensFor(jkt: string, refresh: string, withIdToken: boolean) {
    const now = Math.floor(Date.now() / 1000);
    return json({
        access_token: jwt({ owner: 'person-1', cnf: { jkt }, exp: now + 300 }),
        token_type: 'DPoP',
        expires_in: 300,
        refresh_token: refresh,
        ...(withIdToken
            ? {
                  id_token: jwt({
                      iss: ISSUER,
                      aud: 'shortener-ui',
                      sub: 'person-1',
                      azp: 'shortener-ui',
                      iat: now,
                      exp: now + 300,
                  }),
              }
            : {}),
    });
}

/** A sign-in that has been started with `jkt`, and whose callback is in the location. */
async function completeSignInWith(jkt: string) {
    sessionStorage.setItem(
        PENDING,
        JSON.stringify({ jkt, verifier: oauth.generateRandomCodeVerifier(), state: 'state-1' })
    );
    return completeSignIn(
        new URL('http://localhost:3000/app/auth/callback?code=code-1&state=state-1')
    );
}

describe('the session', () => {
    let browser: ReturnType<typeof stubBrowser>;

    beforeEach(() => {
        browser = stubBrowser();
    });

    afterEach(async () => {
        await signOut();
        vi.unstubAllGlobals();
    });

    it('starts a sign-in with a key of its own, PKCE, and the key bound to the authorization request', async () => {
        answer(browser.fetch, () => tokensFor('unused', 'unused', false));

        await signIn();

        const url = new URL(browser.location.href);
        const pending = JSON.parse(sessionStorage.getItem(PENDING) ?? '{}');
        expect(url.origin + url.pathname).toBe(`${ISSUER}/protocol/openid-connect/auth`);
        expect(url.searchParams.get('client_id')).toBe('shortener-ui');
        expect(url.searchParams.get('redirect_uri')).toBe(
            'http://localhost:3000/app/auth/callback'
        );
        expect(url.searchParams.get('code_challenge_method')).toBe('S256');
        expect(url.searchParams.get('prompt')).toBe('login');
        expect(url.searchParams.get('dpop_jkt')).toBe(pending.jkt);
        expect(url.searchParams.get('scope')).not.toContain('shortlinks:admin');
        expect(await loadKeyRecord(pending.jkt)).toBeDefined();
    });

    it('finishes a sign-in: the token is bound to the key of that sign-in, which keeps its owner and refresh token', async () => {
        const record = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-1', true));
        const listener = vi.fn();
        const unsubscribe = sessionStore.subscribe(listener);

        const session = await completeSignInWith(record.jkt);

        expect(session).toMatchObject({ owner: 'person-1', jkt: record.jkt });
        expect(sessionStore.getSnapshot()).toBe(session);
        expect(listener).toHaveBeenCalled();
        expect(localStorage.getItem(ACTIVE)).toBe(record.jkt);
        expect(await loadKeyRecord(record.jkt)).toMatchObject({
            owner: 'person-1',
            refreshToken: 'refresh-1',
        });
        unsubscribe();
    });

    it('refuses a sign-in that is not in progress, and a callback with the wrong state', async () => {
        await expect(
            completeSignIn(new URL('http://localhost:3000/app/auth/callback?code=c&state=s'))
        ).rejects.toBeInstanceOf(SignInError);

        const record = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-1', true));
        sessionStorage.setItem(
            PENDING,
            JSON.stringify({
                jkt: record.jkt,
                verifier: oauth.generateRandomCodeVerifier(),
                state: 'expected',
            })
        );
        await expect(
            completeSignIn(new URL('http://localhost:3000/app/auth/callback?code=c&state=other'))
        ).rejects.toThrow();
        expect(sessionStore.getSnapshot()).toBeNull();
    });

    it('refuses a token that is not bound to the key of the sign-in', async () => {
        const record = await createKeyRecord();
        const other = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(other.jkt, 'refresh-1', true));

        await expect(completeSignInWith(record.jkt)).rejects.toBeInstanceOf(SignInError);
    });

    it('signs a request with the session key, and sends the body of a request that has one', async () => {
        const record = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-1', true));
        await completeSignInWith(record.jkt);
        browser.fetch.mockImplementation(async (input) => {
            if (addressOf(input).startsWith(API_ROOT)) {
                return json({ items: [] });
            }
            throw new Error('unexpected');
        });

        browser.fetch.mockClear();
        const response = await authorizedFetch(new Request(API));
        expect(response.status).toBe(200);
        const [, init] = browser.fetch.mock.calls[0] as [string, RequestInit];
        const headers = init.headers as Record<string, string>;
        expect(headers.authorization).toMatch(/^DPoP /);
        expect(headers.dpop.split('.')).toHaveLength(3);
        expect(init.body).toBeUndefined();

        await authorizedFetch(
            new Request('http://localhost:3000/api/short-links', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ targetUrl: 'https://example.com' }),
            })
        );
        expect((browser.fetch.mock.calls[1] as [string, RequestInit])[1].body).toBe(
            JSON.stringify({ targetUrl: 'https://example.com' })
        );
    });

    it('answers a DPoP nonce challenge by sending the request again with the nonce', async () => {
        const record = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-1', true));
        await completeSignInWith(record.jkt);
        let calls = 0;
        browser.fetch.mockImplementation(async (input) => {
            if (!addressOf(input).startsWith(API)) {
                throw new Error('unexpected');
            }
            calls += 1;
            return calls === 1
                ? new Response('{}', {
                      status: 401,
                      headers: {
                          'WWW-Authenticate': 'DPoP error="use_dpop_nonce", algs="ES256"',
                          'DPoP-Nonce': 'nonce-1',
                      },
                  })
                : json({ items: [] });
        });

        const response = await authorizedFetch(new Request(API));

        expect(response.status).toBe(200);
        expect(calls).toBe(2);
    });

    it('answers a token the server refuses by refreshing it, and sending the request again', async () => {
        const record = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-1', true));
        await completeSignInWith(record.jkt);
        let refreshed = false;
        let refreshBody = '';
        browser.fetch.mockImplementation(async (input, init) => {
            const url = addressOf(input);
            if (url === TOKEN) {
                refreshed = true;
                refreshBody = init?.body instanceof URLSearchParams ? init.body.toString() : '';
                return tokensFor(record.jkt, 'refresh-2', false);
            }
            if (!url.startsWith(API)) {
                throw new Error('unexpected');
            }
            return refreshed
                ? json({ items: [] })
                : new Response('{}', {
                      status: 401,
                      headers: { 'WWW-Authenticate': 'DPoP error="invalid_token"' },
                  });
        });

        const response = await authorizedFetch(new Request(API));

        expect(response.status).toBe(200);
        expect(refreshBody).toContain('grant_type=refresh_token');
        expect(await loadKeyRecord(record.jkt)).toMatchObject({ refreshToken: 'refresh-2' });
    });

    it('restores the session of the last sign-in from its refresh token, and forgets it when that is refused', async () => {
        const record = await createKeyRecord();
        await saveKeyRecord({ ...record, owner: 'person-1', refreshToken: 'refresh-1' });
        localStorage.setItem(ACTIVE, record.jkt);
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-2', false));

        expect(await restoreSession()).toMatchObject({ owner: 'person-1', jkt: record.jkt });
        await signOut();

        await saveKeyRecord({ ...record, owner: 'person-1', refreshToken: 'refresh-3' });
        localStorage.setItem(ACTIVE, record.jkt);
        answer(browser.fetch, () => json({ error: 'invalid_grant' }, { status: 400 }));

        expect(await restoreSession()).toBeNull();
        expect(await loadKeyRecord(record.jkt)).toBeUndefined();
        expect(localStorage.getItem(ACTIVE)).toBeNull();
    });

    it('has nothing to restore in a browser that never signed in, and refuses to sign a request', async () => {
        expect(await restoreSession()).toBeNull();
        await expect(authorizedFetch(new Request(API))).rejects.toBeInstanceOf(NotSignedInError);
    });

    it('signs out: the session, the active key and its record go', async () => {
        const record = await createKeyRecord();
        answer(browser.fetch, () => tokensFor(record.jkt, 'refresh-1', true));
        await completeSignInWith(record.jkt);

        await signOut();

        expect(sessionStore.getSnapshot()).toBeNull();
        expect(localStorage.getItem(ACTIVE)).toBeNull();
        expect(await loadKeyRecord(record.jkt)).toBeUndefined();
    });
});
