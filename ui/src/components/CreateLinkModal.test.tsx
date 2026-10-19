import { notifications } from '@mantine/notifications';
import { render, screen, userEvent, waitFor } from '../../test-utils';
import { api } from '../api/client';
import { CreateLinkModal } from './CreateLinkModal';

vi.mock('../api/client', () => ({
    api: { POST: vi.fn(), PUT: vi.fn() },
    dpop: { header: { DPoP: '' } },
}));
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }));

const created = (status: number) => ({
    data: { shortUrl: 'http://localhost:8080/abc123', targetUrl: 'https://example.com/one' },
    error: undefined,
    response: new Response(null, { status }),
});

async function fill(target: string, code?: string) {
    await userEvent.type(screen.getByLabelText(/Target URL/), target);
    if (code !== undefined) {
        await userEvent.type(screen.getByLabelText(/Code/), code);
    }
}

describe('CreateLinkModal', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('checks the target before it sends anything', async () => {
        const onClose = vi.fn();
        render(<CreateLinkModal opened onClose={onClose} />);

        await fill('not a url');
        await userEvent.click(screen.getByRole('button', { name: 'Create' }));

        expect(await screen.findByText('Enter an absolute http or https URL.')).toBeInTheDocument();
        expect(api.POST).not.toHaveBeenCalled();
        expect(api.PUT).not.toHaveBeenCalled();
        expect(onClose).not.toHaveBeenCalled();
    });

    it('checks a chosen code', async () => {
        render(<CreateLinkModal opened onClose={vi.fn()} />);

        await fill('https://example.com/one', 'no');
        await userEvent.click(screen.getByRole('button', { name: 'Create' }));

        expect(await screen.findByText('3 to 32 letters, digits, - or _.')).toBeInTheDocument();
        expect(api.PUT).not.toHaveBeenCalled();
    });

    it('creates a link with a generated code, and says so', async () => {
        vi.mocked(api.POST).mockResolvedValue(created(201) as never);
        const onClose = vi.fn();
        render(<CreateLinkModal opened onClose={onClose} />);

        await fill('https://example.com/one');
        await userEvent.click(screen.getByRole('button', { name: 'Create' }));

        await waitFor(() => expect(onClose).toHaveBeenCalled());
        expect(api.POST).toHaveBeenCalledWith('/api/short-links', {
            params: { header: { DPoP: '' } },
            body: { targetUrl: 'https://example.com/one' },
        });
        expect(notifications.show).toHaveBeenCalledWith(
            expect.objectContaining({ title: 'Link created', color: 'green' })
        );
    });

    it('creates a link with a chosen code', async () => {
        vi.mocked(api.PUT).mockResolvedValue(created(201) as never);
        render(<CreateLinkModal opened onClose={vi.fn()} />);

        await fill('https://example.com/one', 'abc123');
        await userEvent.click(screen.getByRole('button', { name: 'Create' }));

        await waitFor(() => expect(api.PUT).toHaveBeenCalled());
        expect(api.PUT).toHaveBeenCalledWith('/api/short-links/{shortCode}', {
            params: { header: { DPoP: '' }, path: { shortCode: 'abc123' } },
            body: { targetUrl: 'https://example.com/one' },
        });
    });

    it('says a taken code is taken, on the code field and in the alert', async () => {
        vi.mocked(api.PUT).mockResolvedValue({
            data: undefined,
            error: { title: 'Conflict', status: 409 },
            response: new Response(null, { status: 409 }),
        } as never);
        const onClose = vi.fn();
        render(<CreateLinkModal opened onClose={onClose} />);

        await fill('https://example.com/other', 'abc123');
        await userEvent.click(screen.getByRole('button', { name: 'Create' }));

        expect(
            await screen.findAllByText('That code is taken, or reserved. Choose another.')
        ).toHaveLength(2);
        expect(onClose).not.toHaveBeenCalled();
    });

    it('says a code that already points to the same target is already there, which is not an error', async () => {
        vi.mocked(api.PUT).mockResolvedValue(created(200) as never);
        const onClose = vi.fn();
        render(<CreateLinkModal opened onClose={onClose} />);

        await fill('https://example.com/one', 'abc123');
        await userEvent.click(screen.getByRole('button', { name: 'Create' }));

        await waitFor(() => expect(onClose).toHaveBeenCalled());
        expect(notifications.show).toHaveBeenCalledWith(
            expect.objectContaining({ title: 'Already yours', color: 'blue' })
        );
        expect(notifications.show).not.toHaveBeenCalledWith(
            expect.objectContaining({ title: 'Link created' })
        );
    });
});
