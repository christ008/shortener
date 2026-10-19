import createClient from 'openapi-fetch';
import { authorizedFetch } from '../auth/session';
import type { paths } from './schema';

/**
 * The typed client of the API. Its types come from `docs/openapi.yaml` (`yarn gen:api`), so a change to the contract that the
 * UI does not follow fails the type check. Every request goes through `authorizedFetch`, which signs it.
 */
export const api = createClient<paths>({ fetch: authorizedFetch });

/**
 * The `DPoP` header the contract requires on every operation. `authorizedFetch` writes the real proof, for the request it
 * signs, so the value given here is replaced and never sent.
 */
export const dpop = { header: { DPoP: '' } } as const;
