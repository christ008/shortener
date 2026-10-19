import mantineCoreStyles from '@mantine/core/styles.css?url';
import mantineNotificationsStyles from '@mantine/notifications/styles.css?url';

import type { ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createRootRoute, HeadContent, Outlet, Scripts } from '@tanstack/react-router';
import { ColorSchemeScript, mantineHtmlProps, MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { config } from '@/config';
import { queryClient } from '@/query';
import { theme } from '@/theme';

export const Route = createRootRoute({
    head: () => ({
        meta: [
            { charSet: 'utf-8' },
            { name: 'viewport', content: 'width=device-width, initial-scale=1' },
            { title: 'Shortener' },
        ],
        links: [
            { rel: 'stylesheet', href: mantineCoreStyles },
            { rel: 'stylesheet', href: mantineNotificationsStyles },
            { rel: 'icon', type: 'image/svg+xml', href: `${config.basePath}/favicon.svg` },
        ],
    }),
    shellComponent: RootDocument,
    component: () => <Outlet />,
});

function RootDocument({ children }: { children: ReactNode }) {
    return (
        <html lang="en" {...mantineHtmlProps}>
            <head>
                <ColorSchemeScript />
                <HeadContent />
            </head>
            <body>
                <MantineProvider theme={theme}>
                    <Notifications position="bottom-right" />
                    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
                </MantineProvider>
                <Scripts />
            </body>
        </html>
    );
}
