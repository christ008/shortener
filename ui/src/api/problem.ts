import type { components } from './schema';

export type Problem = components['schemas']['Problem'];

/**
 * The message a person reads for a failed request, from its status and its problem detail.
 *
 * - 409 on a chosen code means the code is taken, or reserved, or taken for another target.
 * - 429 says when to try again, from `Retry-After`, in seconds.
 * - 503 asks to retry later.
 * - 403 names what was refused, from the detail.
 */
export function messageFor(
    status: number | undefined,
    problem: Problem | undefined,
    retryAfter?: string | null
): string {
    switch (status) {
        case 400:
            return (
                problem?.detail ??
                'Check the link: the target must be an absolute http or https URL.'
            );
        case 401:
            return 'Your sign-in has ended. Sign in again.';
        case 403:
            return problem?.detail ?? 'You are not allowed to do that.';
        case 404:
            return 'That link does not exist, or it is not yours.';
        case 409:
            return 'That code is taken, or reserved. Choose another.';
        case 410:
            return 'That link is disabled.';
        case 429:
            return retryAfter
                ? `Too many requests. Try again in ${retryAfter} seconds.`
                : 'Too many requests. Try again in a moment.';
        case 503:
            return 'The service is not available right now. Try again later.';
        default:
            return problem?.title ?? 'Something went wrong.';
    }
}
