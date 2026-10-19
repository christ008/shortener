import type { ReactElement } from 'react';
import { render, screen } from '../../../test-utils';
import { completeSignIn } from '../../auth/session';
import { Route } from './callback';

const mocks = vi.hoisted(() => ({ loaderData: { problem: '' } }));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@tanstack/react-router')>()),
    createFileRoute: () => (options: object) => ({
        ...options,
        useLoaderData: () => mocks.loaderData,
    }),
    Link: ({ children }: { children: React.ReactNode }) => <a href="/">{children}</a>,
}));
vi.mock('../../auth/session', () => ({ completeSignIn: vi.fn() }));

type Options = { loader: () => Promise<unknown>; component: () => ReactElement };
const options = Route as unknown as Options;

describe('the sign-in callback', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('finishes the sign-in and goes to the links, without keeping the callback in the history', async () => {
        vi.mocked(completeSignIn).mockResolvedValue({} as never);

        await expect(options.loader()).rejects.toMatchObject({
            options: { to: '/links', replace: true },
        });
        expect(completeSignIn).toHaveBeenCalledWith(expect.any(URL));
    });

    it('keeps the problem, and shows it with a way back to the sign-in', async () => {
        vi.mocked(completeSignIn).mockRejectedValue(new Error('the state does not match'));

        const loaded = await options.loader();
        expect(loaded).toEqual({ problem: 'the state does not match' });

        mocks.loaderData = loaded as typeof mocks.loaderData;
        const Page = options.component;
        render(<Page />);
        expect(screen.getByRole('alert')).toHaveTextContent('the state does not match');
        expect(screen.getByRole('link', { name: 'Back to sign-in' })).toBeInTheDocument();
    });

    it('says sign-in failed when the failure has no message', async () => {
        vi.mocked(completeSignIn).mockRejectedValue('not an error');

        expect(await options.loader()).toEqual({ problem: 'Sign-in failed.' });
    });
});
