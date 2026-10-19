import { useEffect, useRef, useState } from 'react';
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router';
import { Anchor, Center, Stack, Text } from '@mantine/core';
import { completeSignIn } from '../../auth/session';
import { ProblemAlert } from '../../components/ProblemAlert';

export const Route = createFileRoute('/auth/callback')({
    component: CallbackPage,
});

function CallbackPage() {
    const navigate = useNavigate();
    const [problem, setProblem] = useState<string | null>(null);
    // The code may be used once. A second run of the effect, from development's strict mode, must not try again.
    const started = useRef(false);

    useEffect(() => {
        if (started.current) {
            return;
        }
        started.current = true;
        completeSignIn(new URL(window.location.href))
            .then(() =>
                navigate({
                    to: '/links',
                    search: { page: 0, sort: 'createdAt,desc' },
                    replace: true,
                })
            )
            .catch((error: unknown) =>
                setProblem(error instanceof Error ? error.message : 'Sign-in failed.')
            );
    }, [navigate]);

    return (
        <Center mih="100vh" p="md">
            <Stack align="center" maw={420}>
                {problem ? (
                    <>
                        <ProblemAlert message={problem} />
                        <Anchor component={Link} to="/">
                            Back to sign-in
                        </Anchor>
                    </>
                ) : (
                    <Text c="dimmed">Signing you in…</Text>
                )}
            </Stack>
        </Center>
    );
}
