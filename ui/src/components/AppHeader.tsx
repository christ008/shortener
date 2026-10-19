import { useSyncExternalStore, type ReactNode } from 'react';
import { useNavigate } from '@tanstack/react-router';
import {
    Button,
    Container,
    Group,
    SegmentedControl,
    Text,
    Title,
    useMantineColorScheme,
} from '@mantine/core';
import { sessionStore, signOut } from '../auth/session';

/** The header of the signed-in pages: the name of the app, the theme, the person and sign-out. */
export function AppHeader({ children }: { children: ReactNode }) {
    const session = useSyncExternalStore(sessionStore.subscribe, sessionStore.getSnapshot);
    const navigate = useNavigate();
    const { colorScheme, setColorScheme } = useMantineColorScheme();

    return (
        <>
            <Container size="lg" py="md">
                <Group justify="space-between" wrap="wrap" gap="md">
                    <Title order={2}>Shortener</Title>
                    <Group gap="md">
                        <SegmentedControl
                            size="xs"
                            aria-label="Theme"
                            value={colorScheme}
                            onChange={(value) => setColorScheme(value as 'light' | 'dark' | 'auto')}
                            data={[
                                { label: 'Light', value: 'light' },
                                { label: 'Dark', value: 'dark' },
                                { label: 'Auto', value: 'auto' },
                            ]}
                        />
                        {session && (
                            <Text size="sm" c="dimmed" title={session.owner}>
                                {session.owner.slice(0, 8)}…
                            </Text>
                        )}
                        <Button
                            variant="default"
                            size="xs"
                            onClick={async () => {
                                await signOut();
                                await navigate({ to: '/' });
                            }}
                        >
                            Sign out
                        </Button>
                    </Group>
                </Group>
            </Container>
            <Container size="lg" pb="xl">
                {children}
            </Container>
        </>
    );
}
