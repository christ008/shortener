import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button, Modal, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { api, dpop } from '../api/client';
import { messageFor, type Problem } from '../api/problem';
import { ProblemAlert } from './ProblemAlert';

type Values = { targetUrl: string; customCode: string };

/**
 * Creates a link. A generated code is a `POST`. A chosen code is a `PUT` to that code: the API answers `201` for a new link and
 * `200` when the same target already had the code, which is not an error.
 */
export function CreateLinkModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
    const queryClient = useQueryClient();
    const [problem, setProblem] = useState<string | null>(null);
    const form = useForm<Values>({
        initialValues: { targetUrl: '', customCode: '' },
        validate: {
            targetUrl: (value) =>
                /^https?:\/\/.+/.test(value.trim()) ? null : 'Enter an absolute http or https URL.',
            customCode: (value) =>
                value === '' || /^[A-Za-z0-9_-]{3,32}$/.test(value)
                    ? null
                    : '3 to 32 letters, digits, - or _.',
        },
    });

    const create = useMutation({
        mutationFn: async (values: Values) => {
            const targetUrl = values.targetUrl.trim();
            if (values.customCode === '') {
                return api.POST('/api/short-links', { params: dpop, body: { targetUrl } });
            }
            return api.PUT('/api/short-links/{shortCode}', {
                params: { ...dpop, path: { shortCode: values.customCode } },
                body: { targetUrl },
            });
        },
        onSuccess: async ({ data, error, response }) => {
            if (error) {
                const message = messageFor(
                    response.status,
                    error as Problem,
                    response.headers.get('Retry-After')
                );
                if (response.status === 409) {
                    form.setFieldError('customCode', message);
                }
                setProblem(message);
                return;
            }
            await queryClient.invalidateQueries({ queryKey: ['links'] });
            notifications.show({
                title: 'Link created',
                message: data?.shortUrl ?? 'Your new link is ready.',
                color: 'green',
            });
            form.reset();
            setProblem(null);
            onClose();
        },
    });

    return (
        <Modal opened={opened} onClose={onClose} title="Create a short link">
            <form onSubmit={form.onSubmit((values) => create.mutate(values))}>
                <Stack>
                    {problem && <ProblemAlert message={problem} />}
                    <TextInput
                        label="Target URL"
                        placeholder="https://example.com/some/long/path"
                        required
                        {...form.getInputProps('targetUrl')}
                    />
                    <TextInput
                        label="Code (optional)"
                        description="Leave it empty for a generated code."
                        {...form.getInputProps('customCode')}
                    />
                    <Button type="submit" loading={create.isPending}>
                        Create
                    </Button>
                </Stack>
            </form>
        </Modal>
    );
}
