import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { api, dpop } from '../api/client';
import { messageFor, type Problem } from '../api/problem';
import type { ShortLink } from './LinkTable';
import { ProblemAlert } from './ProblemAlert';

/** Asks before a link is disabled, because it cannot be enabled again. A disabled link answers `410`. */
export function DisableConfirm({ link, onClose }: { link: ShortLink | null; onClose: () => void }) {
    const queryClient = useQueryClient();
    const [problem, setProblem] = useState<string | null>(null);
    const disable = useMutation({
        mutationFn: (shortCode: string) =>
            api.PATCH('/api/short-links/{shortCode}', {
                params: { ...dpop, path: { shortCode } },
                body: { disabled: true },
            }),
        onSuccess: async ({ error, response }) => {
            if (error) {
                setProblem(
                    messageFor(
                        response.status,
                        error as Problem,
                        response.headers.get('Retry-After')
                    )
                );
                return;
            }
            await queryClient.invalidateQueries({ queryKey: ['links'] });
            notifications.show({
                title: 'Link disabled',
                message: 'It no longer redirects.',
                color: 'gray',
            });
            setProblem(null);
            onClose();
        },
    });

    return (
        <Modal opened={link !== null} onClose={onClose} title="Disable this link?">
            <Stack>
                {problem && <ProblemAlert message={problem} />}
                <Text>
                    {link?.shortUrl} will stop redirecting. A disabled link cannot be enabled again,
                    and its code is never given to another link.
                </Text>
                <Group justify="flex-end">
                    <Button variant="default" onClick={onClose}>
                        Keep it
                    </Button>
                    <Button
                        color="red"
                        loading={disable.isPending}
                        onClick={() => link && disable.mutate(link.shortCode)}
                    >
                        Disable
                    </Button>
                </Group>
            </Stack>
        </Modal>
    );
}
