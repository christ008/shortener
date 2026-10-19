import { getRouter } from './router';

describe('the router', () => {
    it('is built under /app, the path the edge serves the app on', () => {
        expect(getRouter().options.basepath).toBe('/app');
    });
});
