import type { ReactElement } from 'react';
import { render, screen, userEvent, waitFor } from '../../test-utils';
import { api } from '../api/client';
import { restoreSession } from '../auth/session';
import { queryClient } from '../query';
import { Route } from './links';

const mocks = vi.hoisted(() => ({
    navigate: vi.fn(),
    search: { page: 0, sort: 'createdAt,desc' },
}));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@tanstack/react-router')>()),
    createFileRoute: () => (options: object) => ({
        ...options,
        useSearch: () => mocks.search,
        useNavigate: () => mocks.navigate,
    }),
    useNavigate: () => mocks.navigate,
}));
vi.mock('../api/client', () => ({ api: { GET: vi.fn() }, dpop: { header: { DPoP: '' } } }));
vi.mock('../auth/session', () => ({
    restoreSession: vi.fn(),
    signOut: vi.fn(async () => {}),
    sessionStore: { subscribe: () => () => {}, getSnapshot: () => null },
}));

type Options = {
    validateSearch: (search: Record<string, unknown>) => { page: number; sort: string };
    beforeLoad: () => Promise<void>;
    loader: (context: { deps: { page: number; sort: string } }) => Promise<void>;
    component: () => ReactElement;
};
const options = Route as unknown as Options;

const link = (shortCode: string) => ({
    shortCode,
    shortUrl: `http://localhost:8080/${shortCode}`,
    targetUrl: 'https://example.com/one',
    createdBy: 'person-1',
    createdAt: '2026-10-18T12:00:00Z',
    disabledAt: null,
});

const page = (items: ReturnType<typeof link>[], totalPages: number, totalItems: number) => ({
    items,
    page: 0,
    size: 20,
    hasNext: totalPages > 1,
    totalItems,
    totalPages,
});

describe('the links page', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        queryClient.clear();
        mocks.search = { page: 0, sort: 'createdAt,desc' };
    });

    it('reads the page and the sort from the address, and corrects what is not a page', () => {
        expect(options.validateSearch({ page: '3', sort: 'shortCode,asc' })).toEqual({
            page: 3,
            sort: 'shortCode,asc',
        });
        expect(options.validateSearch({ page: '-1', sort: 'totally-invented' })).toEqual({
            page: 0,
            sort: 'createdAt,desc',
        });
        expect(options.validateSearch({})).toEqual({ page: 0, sort: 'createdAt,desc' });
    });

    it('goes to the sign-in page when nobody is signed in', async () => {
        vi.mocked(restoreSession).mockResolvedValue(null);

        await expect(options.beforeLoad()).rejects.toMatchObject({ options: { to: '/' } });
    });

    it('sends a page past the end to the last page, decided from the cached page', async () => {
        vi.mocked(api.GET).mockResolvedValue({
            data: page([], 2, 25),
            error: undefined,
            response: new Response(),
        } as never);

        await expect(
            options.loader({ deps: { page: 5, sort: 'createdAt,desc' } })
        ).rejects.toMatchObject({
            options: { to: '/links', search: { page: 1, sort: 'createdAt,desc' } },
        });
    });

    it('leaves a page that exists alone, and leaves a failed read for the screen to show', async () => {
        vi.mocked(api.GET).mockResolvedValueOnce({
            data: page([link('abc123')], 2, 25),
            error: undefined,
            response: new Response(),
        } as never);
        await expect(
            options.loader({ deps: { page: 0, sort: 'createdAt,desc' } })
        ).resolves.toBeUndefined();

        vi.mocked(api.GET).mockResolvedValueOnce({
            data: undefined,
            error: { title: 'Service Unavailable', status: 503 },
            response: new Response(null, { status: 503 }),
        } as never);
        await expect(
            options.loader({ deps: { page: 9, sort: 'shortCode,asc' } })
        ).resolves.toBeUndefined();
    });

    it('lists the links of the page, and moves between pages through the address', async () => {
        vi.mocked(api.GET).mockResolvedValue({
            data: page([link('abc123'), link('def456')], 2, 25),
            error: undefined,
            response: new Response(),
        } as never);
        const Page = options.component;
        render(<Page />);

        expect(await screen.findByText('abc123')).toBeInTheDocument();
        expect(screen.getByText('def456')).toBeInTheDocument();
        expect(screen.getByText('25 links, page 1 of 2')).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: '2' }));
        await waitFor(() =>
            expect(mocks.navigate).toHaveBeenCalledWith({
                search: { page: 1, sort: 'createdAt,desc' },
            })
        );
    });

    it('says a read failed, with the reason the API gave', async () => {
        vi.mocked(api.GET).mockResolvedValue({
            data: undefined,
            error: { title: 'Service Unavailable', status: 503 },
            response: new Response(null, { status: 503 }),
        } as never);
        const Page = options.component;
        render(<Page />);

        expect(
            await screen.findByText('The service is not available right now. Try again later.')
        ).toBeInTheDocument();
    });

    it('says a page with no links is a page to leave, when links exist on other pages', async () => {
        vi.mocked(api.GET).mockResolvedValue({
            data: page([], 2, 25),
            error: undefined,
            response: new Response(),
        } as never);
        const Page = options.component;
        render(<Page />);

        expect(
            await screen.findByText('This page has no links. Pick another page.')
        ).toBeInTheDocument();
    });

    it('says the list could not load, for a failure that is not from the API', async () => {
        vi.mocked(api.GET).mockRejectedValue(new TypeError('network down'));
        const Page = options.component;
        render(<Page />);

        expect(await screen.findByText('Could not load your links.')).toBeInTheDocument();
    });
});
