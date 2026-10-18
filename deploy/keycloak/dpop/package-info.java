/**
 * The DPoP client of the repository: the reference for the people outside the project, and what the scripts run to sign in and to
 * call the API as a client with a key of its own (RFC 9449).
 *
 * <p>Run it from the root of the repository with {@code java deploy/keycloak/dpop/DpopClient.java COMMAND ...}, with no build step:
 * the JDK 25 source launcher compiles the other classes of this package that the client uses.
 *
 * <ul>
 *   <li>{@link dpop.DpopClient}: the command line, and the commands and their output.
 *   <li>{@code TokenEndpoint}: the token endpoint and the API calls, with the nonce each server asked for.
 *   <li>{@code KeycloakLogin}: the browser part of the login.
 *   <li>{@code Keys} and {@code Jose}: the key material, and the JWS and the proofs.
 *   <li>{@code Http} and {@code Oauth}: the transport, and the names of the protocol.
 *   <li>{@code Json}, {@code Console}, {@code SystemConsole}, {@code Failure} and {@code Refused}: the JSON, the output and the errors.
 * </ul>
 */
package dpop;
