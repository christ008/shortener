import { useState } from 'react';
import { createFileRoute, redirect } from '@tanstack/react-router';
import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { restoreSession, signIn } from '../auth/session';
import { ProblemAlert } from '../components/ProblemAlert';

export const Route = createFileRoute('/')({
    beforeLoad: async () => {
        if (await restoreSession()) {
            throw redirect({ to: '/links', search: { page: 0, sort: 'createdAt,desc' } });
        }
    },
    component: SignInPage,
});

function SignInPage() {
    const [problem, setProblem] = useState<string | null>(null);
    return (
        <Center mih="100vh" p="md">
            <Stack align="center" maw={420}>
                <Title order={1}>Shortener</Title>
                <Text c="dimmed" ta="center">
                    Sign in to create short links and see your own.
                </Text>
                {problem && <ProblemAlert message={problem} />}
                <Button
                    size="md"
                    onClick={() =>
                        signIn().catch((error: unknown) =>
                            setProblem(error instanceof Error ? error.message : 'Sign-in failed.')
                        )
                    }
                >
                    Sign in
                </Button>
            </Stack>
        </Center>
    );
}
