import { Alert } from '@mantine/core';

/** A failed request, said in words the person can act on. */
export function ProblemAlert({ message }: { message: string }) {
    return (
        <Alert color="red" role="alert" title="Something did not work">
            {message}
        </Alert>
    );
}
