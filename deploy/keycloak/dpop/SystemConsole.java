package dpop;


/** The process's own output. The only place that writes to System.out and System.err. */
class SystemConsole implements Console {
@Override
public void out(String text) {
    System.out.println(text);
}

@Override
public void err(String text) {
    System.err.println(text);
}

@Override
public void trace(Throwable error) {
    error.printStackTrace(System.err);
}
}
