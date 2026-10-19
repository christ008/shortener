import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render as testingLibraryRender } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { theme } from '../src/theme';

/** Renders with the providers the app has: its theme, and a query cache that does not retry a failed request. */
export function render(ui: React.ReactNode) {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return testingLibraryRender(ui, {
        wrapper: ({ children }: { children: React.ReactNode }) => (
            <QueryClientProvider client={client}>
                <MantineProvider theme={theme} env="test">
                    {children}
                </MantineProvider>
            </QueryClientProvider>
        ),
    });
}
