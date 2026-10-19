import { createFileRoute, Link, redirect } from '@tanstack/react-router';
import { Anchor, Center, Stack } from '@mantine/core';
import { completeSignIn } from '../../auth/session';
import { ProblemAlert } from '../../components/ProblemAlert';

/**
 * Where the identity provider sends the person back. The code is exchanged once, by the loader: a route is not preloaded, so a
 * link that is hovered cannot spend the code first. A success goes to the links, and a failure is shown here.
 */
export const Route = createFileRoute('/auth/callback')({
    preload: false,
    loader: async () => {
        try {
            await completeSignIn(new URL(window.location.href));
        } catch (error) {
            return { problem: error instanceof Error ? error.message : 'Sign-in failed.' };
        }
        throw redirect({
            to: '/links',
            search: { page: 0, sort: 'createdAt,desc' },
            replace: true,
        });
    },
    component: CallbackPage,
});

function CallbackPage() {
    const { problem } = Route.useLoaderData();
    return (
        <Center mih="100vh" p="md">
            <Stack align="center" maw={420}>
                <ProblemAlert message={problem} />
                <Anchor component={Link} to="/">
                    Back to sign-in
                </Anchor>
            </Stack>
        </Center>
    );
}
