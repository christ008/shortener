import { render, screen, userEvent, waitFor } from '../../test-utils';
import { signOut } from '../auth/session';
import { AppHeader } from './AppHeader';

const mocks = vi.hoisted(() => ({ navigate: vi.fn(), session: { owner: 'person-abcdef-1234' } }));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@tanstack/react-router')>()),
    useNavigate: () => mocks.navigate,
}));
vi.mock('../auth/session', () => ({
    sessionStore: {
        subscribe: () => () => {},
        // The same object every time, as the session store keeps it: a new one would make React render again without end.
        getSnapshot: () => mocks.session,
    },
    signOut: vi.fn(async () => {}),
}));

describe('AppHeader', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('shows the page, the person it is for and the theme choice', () => {
        render(
            <AppHeader>
                <p>the page</p>
            </AppHeader>
        );

        expect(screen.getByText('the page')).toBeInTheDocument();
        expect(screen.getByText('person-a…')).toHaveAttribute('title', 'person-abcdef-1234');
        expect(screen.getByRole('radio', { name: 'Light' })).toBeChecked();
    });

    it('signs out and goes to the sign-in page', async () => {
        render(<AppHeader>{null}</AppHeader>);

        await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

        await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith({ to: '/' }));
        expect(signOut).toHaveBeenCalled();
    });

    it('changes the theme', async () => {
        render(<AppHeader>{null}</AppHeader>);

        await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));

        await waitFor(() =>
            expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'dark')
        );
    });
});
