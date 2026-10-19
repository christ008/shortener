import { notifications } from '@mantine/notifications';
import { render, screen, userEvent, waitFor } from '../../test-utils';
import { api } from '../api/client';
import { DisableConfirm } from './DisableConfirm';
import type { ShortLink } from './LinkTable';

vi.mock('../api/client', () => ({
    api: { PATCH: vi.fn() },
    dpop: { header: { DPoP: '' } },
}));
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }));

const link: ShortLink = {
    shortCode: 'abc123',
    shortUrl: 'http://localhost:8080/abc123',
    targetUrl: 'https://example.com/one',
    createdBy: 'person-1',
    createdAt: '2026-10-18T12:00:00Z',
    disabledAt: null,
};

describe('DisableConfirm', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('asks before it disables, and can be kept', async () => {
        const onClose = vi.fn();
        render(<DisableConfirm link={link} onClose={onClose} />);

        expect(screen.getByText(/cannot be enabled again/)).toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: 'Keep it' }));

        expect(onClose).toHaveBeenCalled();
        expect(api.PATCH).not.toHaveBeenCalled();
    });

    it('disables the link, and says so', async () => {
        vi.mocked(api.PATCH).mockResolvedValue({
            data: {},
            error: undefined,
            response: new Response(null, { status: 200 }),
        } as never);
        const onClose = vi.fn();
        render(<DisableConfirm link={link} onClose={onClose} />);

        await userEvent.click(screen.getByRole('button', { name: 'Disable' }));

        await waitFor(() => expect(onClose).toHaveBeenCalled());
        expect(api.PATCH).toHaveBeenCalledWith('/api/short-links/{shortCode}', {
            params: { header: { DPoP: '' }, path: { shortCode: 'abc123' } },
            body: { disabled: true },
        });
        expect(notifications.show).toHaveBeenCalledWith(
            expect.objectContaining({ title: 'Link disabled' })
        );
    });

    it('shows why it failed, and stays open', async () => {
        vi.mocked(api.PATCH).mockResolvedValue({
            data: undefined,
            error: { title: 'Not Found', status: 404 },
            response: new Response(null, { status: 404 }),
        } as never);
        const onClose = vi.fn();
        render(<DisableConfirm link={link} onClose={onClose} />);

        await userEvent.click(screen.getByRole('button', { name: 'Disable' }));

        expect(
            await screen.findByText('That link does not exist, or it is not yours.')
        ).toBeInTheDocument();
        expect(onClose).not.toHaveBeenCalled();
    });

    it('has nothing to ask about when no link is chosen', () => {
        render(<DisableConfirm link={null} onClose={vi.fn()} />);

        expect(screen.queryByRole('button', { name: 'Disable' })).not.toBeInTheDocument();
    });
});
