import { ActionIcon, Anchor, Badge, CopyButton, Group, Table, Text, Tooltip } from '@mantine/core';
import type { components } from '../api/schema';

export type ShortLink = components['schemas']['ShortLink'];

/** The links of a page. A link can be disabled once: its code stays with it, so no other link gets it. */
export function LinkTable({
    items,
    onDisable,
}: {
    items: ShortLink[];
    onDisable: (link: ShortLink) => void;
}) {
    if (items.length === 0) {
        return <Text c="dimmed">No links yet. Create one to see it here.</Text>;
    }
    return (
        <Table.ScrollContainer minWidth={640}>
            <Table striped highlightOnHover>
                <Table.Thead>
                    <Table.Tr>
                        <Table.Th>Short link</Table.Th>
                        <Table.Th>Target</Table.Th>
                        <Table.Th>Created</Table.Th>
                        <Table.Th>Status</Table.Th>
                        <Table.Th />
                    </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                    {items.map((link) => (
                        <Table.Tr key={link.shortCode}>
                            <Table.Td>
                                <Group gap="xs" wrap="nowrap">
                                    <Anchor href={link.shortUrl} target="_blank" rel="noreferrer">
                                        {link.shortCode}
                                    </Anchor>
                                    <CopyButton value={link.shortUrl}>
                                        {({ copied, copy }) => (
                                            <Tooltip
                                                label={copied ? 'Copied' : 'Copy the short link'}
                                            >
                                                <ActionIcon
                                                    variant="subtle"
                                                    onClick={copy}
                                                    aria-label={`Copy ${link.shortUrl}`}
                                                >
                                                    {copied ? '✓' : '⧉'}
                                                </ActionIcon>
                                            </Tooltip>
                                        )}
                                    </CopyButton>
                                </Group>
                            </Table.Td>
                            <Table.Td>
                                <Text size="sm" truncate maw={320}>
                                    {link.targetUrl}
                                </Text>
                            </Table.Td>
                            <Table.Td>
                                <Text size="sm">{new Date(link.createdAt).toLocaleString()}</Text>
                            </Table.Td>
                            <Table.Td>
                                {link.disabledAt ? (
                                    <Badge color="gray">Disabled</Badge>
                                ) : (
                                    <Badge color="green">Active</Badge>
                                )}
                            </Table.Td>
                            <Table.Td>
                                {!link.disabledAt && (
                                    <Anchor
                                        component="button"
                                        c="red"
                                        size="sm"
                                        onClick={() => onDisable(link)}
                                    >
                                        Disable
                                    </Anchor>
                                )}
                            </Table.Td>
                        </Table.Tr>
                    ))}
                </Table.Tbody>
            </Table>
        </Table.ScrollContainer>
    );
}
