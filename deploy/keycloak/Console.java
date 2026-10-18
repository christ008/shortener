
/** Where the client writes: what it prints for the person or the script, and what it says about a failure. */
interface Console {
void out(String text);

void err(String text);

void trace(Throwable error);
}
