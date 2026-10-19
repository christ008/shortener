import { tanstackStart } from '@tanstack/react-start/plugin/vite';
import react from '@vitejs/plugin-react';
import { oxfmt, oxlint } from 'oxc-config-mantine';
import { visualizer } from 'rollup-plugin-visualizer';
import { defineConfig, lazyPlugins } from 'vite-plus';

export default defineConfig({
    fmt: {
        ...oxfmt,
        ignorePatterns: [
            ...oxfmt.ignorePatterns,
            'dist',
            '.output',
            '.tanstack',
            'src/routeTree.gen.ts',
            '*.html',
            '*.yml',
            '*.json',
            '*.css',
        ],
    },
    lint: {
        ...oxlint,
        ignorePatterns: [
            '**/*.{mjs,cjs,js,d.ts,d.mts}',
            'dist',
            '.output',
            '.tanstack',
            'src/routeTree.gen.ts',
        ],
        options: { typeAware: true, typeCheck: true },
    },
    base: '/app/',
    server: {
        port: 3000,
        // The API of a local stack (`./gradlew bootRun`), so the page and the API share an origin as they do behind the edge.
        proxy: {
            // The API builds the URL of each DPoP proof from the request, so it must see the origin the browser used:
            // the Host is kept, and the forwarded headers say the same, as the edge does.
            '/api': {
                target: process.env.VITE_API ?? 'http://localhost:8080',
                changeOrigin: false,
                xfwd: true,
            },
            // A short link, `GET /{code}`, is followed on the API: the same codes the service accepts, but not `app`, which is
            // reserved for the app itself.
            '^/(?!app$)[A-Za-z0-9_-]{3,32}$': {
                target: process.env.VITE_API ?? 'http://localhost:8080',
                changeOrigin: false,
                xfwd: true,
            },
        },
    },
    resolve: {
        tsconfigPaths: true,
    },
    plugins: lazyPlugins(() => [
        tanstackStart({ spa: { enabled: true, prerender: { enabled: false } } }),
        react(),
        {
            ...visualizer({ filename: 'dist/stats.html', gzipSize: true, open: true }),
            apply: (_config, { mode }) => mode === 'analyze',
            applyToEnvironment: (environment) => environment.name === 'client',
        },
    ]),
});
