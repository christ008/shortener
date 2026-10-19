/**
 * Where the app finds its identity provider and client, and the path it is served under.
 *
 * - `issuer` is the realm of the identity provider. The development default is the one `compose.yaml` runs.
 * - `clientId` is the public client `shortener-ui`, which signs users in with PKCE and binds their tokens to a browser key.
 * - `scope` is what a user may ask for. The administrator scope is not asked for: that is a later phase.
 */
export const config = {
    issuer: import.meta.env.VITE_ISSUER ?? 'http://localhost:8180/realms/shortener',
    clientId: 'shortener-ui',
    scope: 'openid shortlinks:read shortlinks:create shortlinks:claim shortlinks:delete',
    basePath: '/app',
} as const;

export const redirectUri = () => `${window.location.origin}${config.basePath}/auth/callback`;
