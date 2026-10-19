import { config, redirectUri } from './config';
import { queryClient } from './query';

describe('the configuration', () => {
    it('serves the app under /app, for the client the identity provider knows', () => {
        expect(config.basePath).toBe('/app');
        expect(config.clientId).toBe('shortener-ui');
        expect(config.scope.split(' ')).not.toContain('shortlinks:admin');
    });

    it('returns to the callback of the app on its own origin', () => {
        expect(redirectUri()).toBe(`${window.location.origin}/app/auth/callback`);
    });

    it('caches the answers of the API without retrying a failed one', () => {
        expect(queryClient.getDefaultOptions().queries?.retry).toBe(false);
    });
});
