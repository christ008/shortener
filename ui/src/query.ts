import { QueryClient } from '@tanstack/react-query';

/** The cache of the API's answers. A failed read is not retried: its problem is shown, and the person can try again. */
export const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});
