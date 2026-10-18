
/** The identity provider answered, and said no. */
class Refused extends Failure {
private static final long serialVersionUID = 1L;

Refused(int status, String body) {
    super("the token endpoint answered " + status + ": " + body);
}
}
