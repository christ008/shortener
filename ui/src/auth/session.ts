import * as oauth from 'oauth4webapi';
import { config, redirectUri } from '../config';
import {
    createKeyRecord,
    deleteKeyRecord,
    keepOnlyKeyRecords,
    loadKeyRecord,
    saveKeyRecord,
    type KeyRecord,
} from './keys';

/**
 * The signed-in person, held in memory only.
 *
 * - `owner` is the person's id on the service (the `owner` claim), and `jkt` the thumbprint of the browser key their tokens
 *   are bound to. `handle` signs every request and keeps the server's DPoP nonce.
 * - The access token is refreshed when it is near its end, with the refresh token kept beside the key. A refresh that the
 *   identity provider refuses ends the session.
 */
export type Session = {
    owner: string;
    jkt: string;
    handle: oauth.DPoPHandle;
    accessToken: string;
    expiresAt: number;
};

/** There is no session, or the identity provider refused to refresh it: the person must sign in again. */
export class NotSignedInError extends Error {}

/** A sign-in that cannot finish: no sign-in was started, or the token is not what this sign-in asked for. */
export class SignInError extends Error {}

const PENDING_KEY = 'shortener-ui.pending';
const ACTIVE_KEY = 'shortener-ui.active';
const REFRESH_MARGIN_MS = 30_000;

const client: oauth.Client = { client_id: config.clientId };
const clientAuth = oauth.None();
const insecure = config.issuer.startsWith('http://');
// Only the identity provider of a development setup is plain HTTP, and the library refuses that unless asked.
// oxlint-disable-next-line typescript/no-deprecated
const requestOptions = { [oauth.allowInsecureRequests]: insecure };

let current: Session | null = null;
let refreshing: Promise<Session> | null = null;
let authorizationServerCache: Promise<oauth.AuthorizationServer> | undefined;
const listeners = new Set<() => void>();

/** For React: the signed-in session, or null, and a way to hear about a change. */
export const sessionStore = {
    subscribe: (listener: () => void) => {
        listeners.add(listener);
        return () => listeners.delete(listener);
    },
    getSnapshot: () => current,
};

function publish(session: Session | null) {
    current = session;
    listeners.forEach((listener) => listener());
}

function authorizationServer(): Promise<oauth.AuthorizationServer> {
    authorizationServerCache ??= (async () => {
        const issuer = new URL(config.issuer);
        const response = await oauth.discoveryRequest(issuer, {
            algorithm: 'oidc',
            ...requestOptions,
        });
        return oauth.processDiscoveryResponse(issuer, response);
    })();
    return authorizationServerCache;
}

/** Sends a request that may need a DPoP nonce, and sends it again with the one the server asks for. */
async function withNonce<T>(
    send: () => Promise<Response>,
    process: (response: Response) => Promise<T>
): Promise<T> {
    try {
        return await process(await send());
    } catch (error) {
        if (!oauth.isDPoPNonceError(error)) {
            throw error;
        }
        return process(await send());
    }
}

type AccessClaims = { owner?: unknown; cnf?: { jkt?: unknown } };

/** Reads the token's claims without checking its signature: the token came from the identity provider over TLS just now. */
function claimsOf(accessToken: string, jkt: string): { owner: string } {
    const payload = accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(
        atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '='))
    ) as AccessClaims;
    if (typeof claims.owner !== 'string') {
        throw new SignInError('the token names no owner');
    }
    if (claims.cnf?.jkt !== jkt) {
        throw new SignInError('the token is not bound to the key of this sign-in');
    }
    return { owner: claims.owner };
}

/** Starts a sign-in with a new browser key. `prompt=login` asks for the password even when the identity provider has a session. */
export async function signIn(): Promise<void> {
    const as = await authorizationServer();
    const record = await createKeyRecord();
    const verifier = oauth.generateRandomCodeVerifier();
    const state = oauth.generateRandomState();
    sessionStorage.setItem(PENDING_KEY, JSON.stringify({ jkt: record.jkt, verifier, state }));
    const url = new URL(as.authorization_endpoint as string);
    url.search = new URLSearchParams({
        response_type: 'code',
        client_id: config.clientId,
        redirect_uri: redirectUri(),
        scope: config.scope,
        code_challenge: await oauth.calculatePKCECodeChallenge(verifier),
        code_challenge_method: 'S256',
        state,
        dpop_jkt: record.jkt,
        prompt: 'login',
    }).toString();
    window.location.assign(url);
}

/** Finishes a sign-in from the URL the identity provider redirected to. */
export async function completeSignIn(callback: URL): Promise<Session> {
    const pending = JSON.parse(sessionStorage.getItem(PENDING_KEY) ?? 'null') as {
        jkt: string;
        verifier: string;
        state: string;
    } | null;
    sessionStorage.removeItem(PENDING_KEY);
    if (!pending) {
        throw new SignInError('no sign-in is in progress');
    }
    const as = await authorizationServer();
    const params = oauth.validateAuthResponse(as, client, callback, pending.state);
    const record = await loadKeyRecord(pending.jkt);
    if (!record) {
        throw new SignInError('the key of this sign-in is gone');
    }
    const tokenHandle = oauth.DPoP(client, record.pair);
    const tokens = await withNonce(
        () =>
            oauth.authorizationCodeGrantRequest(
                as,
                client,
                clientAuth,
                params,
                redirectUri(),
                pending.verifier,
                { DPoP: tokenHandle, ...requestOptions }
            ),
        (response) => oauth.processAuthorizationCodeResponse(as, client, response)
    );
    const { owner } = claimsOf(tokens.access_token, pending.jkt);
    await keepOnlyKeyRecords([pending.jkt]);
    await saveKeyRecord({ ...record, owner, refreshToken: tokens.refresh_token });
    localStorage.setItem(ACTIVE_KEY, pending.jkt);
    const session = sessionFrom(
        tokens.access_token,
        tokens.expires_in ?? 300,
        pending.jkt,
        owner,
        oauth.DPoP(client, record.pair)
    );
    publish(session);
    return session;
}

function sessionFrom(
    accessToken: string,
    expiresIn: number,
    jkt: string,
    owner: string,
    handle: oauth.DPoPHandle
): Session {
    return { owner, jkt, handle, accessToken, expiresAt: Date.now() + expiresIn * 1000 };
}

/**
 * One refresh at a time. The identity provider rotates refresh tokens, so a second refresh with the same token is refused,
 * and it would end the session. Callers that arrive while one is running share its result.
 */
function refreshShared(record: KeyRecord): Promise<Session> {
    refreshing ??= refresh(record).finally(() => {
        refreshing = null;
    });
    return refreshing;
}

async function refresh(record: KeyRecord): Promise<Session> {
    if (!record.owner || !record.refreshToken) {
        throw new NotSignedInError('no refresh token for this key');
    }
    const as = await authorizationServer();
    // The token endpoint and the API keep their own DPoP nonces. The API's handle lives across refreshes, so its nonce is kept.
    const apiHandle =
        current?.jkt === record.jkt ? current.handle : oauth.DPoP(client, record.pair);
    const refreshToken = record.refreshToken;
    const tokenHandle = oauth.DPoP(client, record.pair);
    try {
        const tokens = await withNonce(
            () =>
                oauth.refreshTokenGrantRequest(as, client, clientAuth, refreshToken, {
                    DPoP: tokenHandle,
                    ...requestOptions,
                }),
            (response) => oauth.processRefreshTokenResponse(as, client, response)
        );
        const { owner } = claimsOf(tokens.access_token, record.jkt);
        // The identity provider rotates refresh tokens: the new one replaces the old.
        await saveKeyRecord({
            ...record,
            owner,
            refreshToken: tokens.refresh_token ?? refreshToken,
        });
        const session = sessionFrom(
            tokens.access_token,
            tokens.expires_in ?? 300,
            record.jkt,
            owner,
            apiHandle
        );
        publish(session);
        return session;
    } catch (error) {
        publish(null);
        throw new NotSignedInError(error instanceof Error ? error.message : 'refresh failed');
    }
}

/** Restores the session of the last sign-in in this browser, from its refresh token. Null when there is none to restore. */
export async function restoreSession(): Promise<Session | null> {
    if (current) {
        return current;
    }
    const jkt = localStorage.getItem(ACTIVE_KEY);
    if (!jkt) {
        return null;
    }
    const record = await loadKeyRecord(jkt);
    if (!record) {
        localStorage.removeItem(ACTIVE_KEY);
        return null;
    }
    try {
        return await refreshShared(record);
    } catch {
        await deleteKeyRecord(jkt);
        localStorage.removeItem(ACTIVE_KEY);
        return null;
    }
}

/** The session to sign a request with: refreshed when its access token is near its end, or when `force` says so. */
async function usableSession(force = false): Promise<Session> {
    if (current && !force && current.expiresAt - Date.now() > REFRESH_MARGIN_MS) {
        return current;
    }
    const jkt = current?.jkt ?? localStorage.getItem(ACTIVE_KEY);
    if (!jkt) {
        throw new NotSignedInError('not signed in');
    }
    const record = await loadKeyRecord(jkt);
    if (!record) {
        throw new NotSignedInError('the key of the session is gone');
    }
    return refreshShared(record);
}

/**
 * The `fetch` of the API client: signs the request with the session's key.
 *
 * - A challenge for a fresh DPoP nonce is answered by sending the request again: the handle has learned the nonce.
 * - A token the server refuses is answered by refreshing it, and sending the request once more.
 * `oauth4webapi` reports both as thrown errors, not as responses.
 */
export async function authorizedFetch(request: Request): Promise<Response> {
    // A GET or HEAD has no body, and Firefox refuses any body on one, even an empty string that `text()` returns for a request
    // whose body is not null. So the body is read only for the methods that carry one.
    const body =
        request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.text();
    const headers = new Headers(request.headers);
    headers.delete('Authorization');
    headers.delete('DPoP');
    // Relative to the page: the API client asks for its paths from the page's own origin, and only the browser has one.
    const url = new URL(request.url, window.location.href);
    const send = async (force: boolean) => {
        const session = await usableSession(force);
        return oauth.protectedResourceRequest(
            session.accessToken,
            request.method,
            url,
            headers,
            body,
            { DPoP: session.handle, ...requestOptions }
        );
    };
    try {
        return await send(false);
    } catch (error) {
        if (oauth.isDPoPNonceError(error)) {
            return send(false);
        }
        if (error instanceof oauth.WWWAuthenticateChallengeError) {
            return send(true);
        }
        throw error;
    }
}

/** Forgets the signed-in account in this browser: its key, its refresh token and the session. */
export async function signOut(): Promise<void> {
    const jkt = current?.jkt ?? localStorage.getItem(ACTIVE_KEY);
    localStorage.removeItem(ACTIVE_KEY);
    if (jkt) {
        await deleteKeyRecord(jkt);
    }
    publish(null);
}
