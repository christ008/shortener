import { useState } from 'react';
import { queryOptions, useQuery } from '@tanstack/react-query';
import { createFileRoute, redirect } from '@tanstack/react-router';
import { Alert, Button, Group, Pagination, Select, Stack, Text } from '@mantine/core';
import { api, dpop } from '../api/client';
import { messageFor, type Problem } from '../api/problem';
import { restoreSession } from '../auth/session';
import { AppHeader } from '../components/AppHeader';
import { CreateLinkModal } from '../components/CreateLinkModal';
import { DisableConfirm } from '../components/DisableConfirm';
import { LinkTable, type ShortLink } from '../components/LinkTable';
import { ProblemAlert } from '../components/ProblemAlert';
import { queryClient } from '../query';

const PAGE_SIZE = 20;
const SORTS = ['createdAt,desc', 'createdAt,asc', 'shortCode,asc'] as const;
type Sort = (typeof SORTS)[number];

type LinksSearch = { page: number; sort: Sort };

/** A failed read, with the message to show. */
class ReadError extends Error {}

/** One page of the person's links, in the query cache: the screen and the route's loader read the same entry. */
const linksQuery = (page: number, sort: Sort) =>
    queryOptions({
        queryKey: ['links', page, sort],
        queryFn: async () => {
            const { data, error, response } = await api.GET('/api/short-links', {
                params: { ...dpop, query: { page, size: PAGE_SIZE, sort } },
            });
            if (error) {
                throw new ReadError(
                    messageFor(
                        response.status,
                        error as Problem,
                        response.headers.get('Retry-After')
                    )
                );
            }
            return data;
        },
    });

export const Route = createFileRoute('/links')({
    validateSearch: (search: Record<string, unknown>): LinksSearch => {
        const page = Number(search.page);
        return {
            // A page that is not a number, or is negative, is the first one.
            page: Number.isInteger(page) && page >= 0 ? page : 0,
            sort: SORTS.includes(search.sort as Sort) ? (search.sort as Sort) : 'createdAt,desc',
        };
    },
    loaderDeps: ({ search }) => ({ page: search.page, sort: search.sort }),
    beforeLoad: async () => {
        if (!(await restoreSession())) {
            throw redirect({ to: '/' });
        }
    },
    // A page past the end, after links were removed, goes to the last page. The loader decides it from the cached page, so
    // the screen never renders an empty table for a page that does not exist. A read that fails is shown by the screen.
    loader: async ({ deps }) => {
        const data = await queryClient
            .query(linksQuery(deps.page, deps.sort))
            .catch(() => undefined);
        const lastPage = Math.max(0, (data?.totalPages ?? 1) - 1);
        if (data && deps.page > lastPage) {
            throw redirect({ to: '/links', search: { page: lastPage, sort: deps.sort } });
        }
    },
    component: LinksPage,
});

function LinksPage() {
    const { page, sort } = Route.useSearch();
    const navigate = Route.useNavigate();
    const [creating, setCreating] = useState(false);
    const [disabling, setDisabling] = useState<ShortLink | null>(null);

    const links = useQuery(linksQuery(page, sort));

    const failure =
        links.error instanceof ReadError
            ? links.error.message
            : links.error
              ? 'Could not load your links.'
              : null;

    return (
        <AppHeader>
            <Stack gap="md">
                <Group justify="space-between" wrap="wrap">
                    <Select
                        aria-label="Sort"
                        value={sort}
                        allowDeselect={false}
                        w={220}
                        onChange={(value) =>
                            value && navigate({ search: { page: 0, sort: value as Sort } })
                        }
                        data={[
                            { value: 'createdAt,desc', label: 'Newest first' },
                            { value: 'createdAt,asc', label: 'Oldest first' },
                            { value: 'shortCode,asc', label: 'Code, A to Z' },
                        ]}
                    />
                    <Button onClick={() => setCreating(true)}>Create a link</Button>
                </Group>

                {failure && <ProblemAlert message={failure} />}
                {links.isPending && <Text c="dimmed">Loading…</Text>}
                {links.data && (
                    <>
                        <LinkTable items={links.data.items} onDisable={setDisabling} />
                        {links.data.totalPages > 1 && (
                            <Group justify="space-between" wrap="wrap">
                                <Text size="sm" c="dimmed">
                                    {links.data.totalItems} links, page {page + 1} of{' '}
                                    {links.data.totalPages}
                                </Text>
                                <Pagination
                                    total={links.data.totalPages}
                                    value={page + 1}
                                    onChange={(value) =>
                                        navigate({ search: { page: value - 1, sort } })
                                    }
                                />
                            </Group>
                        )}
                    </>
                )}
                {links.data && links.data.items.length === 0 && links.data.totalItems > 0 && (
                    <Alert color="gray">This page has no links. Pick another page.</Alert>
                )}
            </Stack>

            <CreateLinkModal opened={creating} onClose={() => setCreating(false)} />
            <DisableConfirm link={disabling} onClose={() => setDisabling(null)} />
        </AppHeader>
    );
}
