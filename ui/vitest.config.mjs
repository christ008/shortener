import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite-plus';

export default defineConfig({
    plugins: [react()],
    resolve: {
        tsconfigPaths: true,
    },
    test: {
        globals: true,
        environment: 'jsdom',
        setupFiles: './vitest.setup.mjs',
        // lcov is what SonarCloud reads (sonar.javascript.lcov.reportPaths, in build.gradle.kts). The generated files are not
        // code the app wrote: the route tree and the API types.
        coverage: {
            provider: 'v8',
            include: ['src/**/*.{ts,tsx}'],
            exclude: ['src/routeTree.gen.ts', 'src/api/schema.ts'],
            reporter: ['text', 'lcov'],
            reportsDirectory: 'coverage',
        },
    },
});
