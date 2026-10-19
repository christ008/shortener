import { render, screen, userEvent } from '../../test-utils';
import { LinkTable, type ShortLink } from './LinkTable';

const active: ShortLink = {
    shortCode: 'abc123',
    shortUrl: 'http://localhost:8080/abc123',
    targetUrl: 'https://example.com/one',
    createdBy: 'person-1',
    createdAt: '2026-10-18T12:00:00Z',
    disabledAt: null,
};
const disabled: ShortLink = {
    ...active,
    shortCode: 'def456',
    shortUrl: 'http://localhost:8080/def456',
    disabledAt: '2026-10-18T13:00:00Z',
};

describe('LinkTable', () => {
    it('shows each link with its state, and offers to disable only the active ones', async () => {
        const onDisable = vi.fn();
        render(<LinkTable items={[active, disabled]} onDisable={onDisable} />);

        expect(screen.getByText('abc123')).toBeInTheDocument();
        expect(screen.getByText('Active')).toBeInTheDocument();
        expect(screen.getByText('Disabled')).toBeInTheDocument();

        const disable = screen.getAllByRole('button', { name: 'Disable' });
        expect(disable).toHaveLength(1);
        await userEvent.click(disable[0]);
        expect(onDisable).toHaveBeenCalledWith(active);
    });

    it('has a copy button for every short link', () => {
        render(<LinkTable items={[active]} onDisable={vi.fn()} />);

        expect(screen.getByRole('button', { name: `Copy ${active.shortUrl}` })).toBeInTheDocument();
    });

    it('says there are no links yet, when the page is empty', () => {
        render(<LinkTable items={[]} onDisable={vi.fn()} />);

        expect(screen.getByText(/No links yet/)).toBeInTheDocument();
    });
});
