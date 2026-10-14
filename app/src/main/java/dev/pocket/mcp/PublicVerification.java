package dev.pocket.mcp;

import java.util.function.BooleanSupplier;

/** Retry only read-only public health probes; authentication failures remain closed. */
final class PublicVerification {
    interface Probe {int check(boolean authenticated) throws Exception;}
    interface Pause {void waitBeforeRetry(int attempt) throws InterruptedException;}
    static boolean verify(Probe probe,BooleanSupplier stopped,Pause pause) {
        for(int attempt=0;attempt<3 && !stopped.getAsBoolean();attempt++) {
            try {
                int unauthenticated=probe.check(false);
                if(unauthenticated==200) return false;
                if(unauthenticated==401 && !stopped.getAsBoolean()) {
                    int authenticated=probe.check(true);
                    if(authenticated==200) return true;
                    if(authenticated==401 || authenticated==403) return false;
                }
            } catch(Exception ignored) {
                // An SDK/HTTP exception can include the request credentials. Retain no raw details.
            }
            if(attempt<2 && !stopped.getAsBoolean()) {
                try {pause.waitBeforeRetry(attempt+1);}
                catch(InterruptedException e) {Thread.currentThread().interrupt();return false;}
            }
        }
        return false;
    }
}
