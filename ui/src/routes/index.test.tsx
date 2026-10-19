import type { ReactElement } from 'react';
import { render, screen, userEvent, waitFor } from '../../test-utils';
import { restoreSession, signIn } from '../auth/session';
import { Route } from './index';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@tanstack/react-router')>()),
    createFileRoute: () => (options: object) => options,
}));
vi.mock('../auth/session', () => ({
    restoreSession: vi.fn(),
    signIn: vi.fn(async () => {}),
}));

type Options = { beforeLoad: () => Promise<void>; component: () => ReactElement };
const options = Route as unknown as Options;

describe('the sign-in page', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('goes straight to the links when a session can be restored', async () => {
        vi.mocked(restoreSession).mockResolvedValue({} as never);

        await expect(options.beforeLoad()).rejects.toMatchObject({ options: { to: '/links' } });
    });

    it('stays here when nobody is signed in', async () => {
        vi.mocked(restoreSession).mockResolvedValue(null);

        await expect(options.beforeLoad()).resolves.toBeUndefined();
    });

    it('starts the sign-in, and says why it could not', async () => {
        const Page = options.component;
        render(<Page />);

        await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
        await waitFor(() => expect(signIn).toHaveBeenCalled());

        vi.mocked(signIn).mockRejectedValueOnce(new Error('the identity provider is not there'));
        await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
        expect(await screen.findByText('the identity provider is not there')).toBeInTheDocument();
    });
});
