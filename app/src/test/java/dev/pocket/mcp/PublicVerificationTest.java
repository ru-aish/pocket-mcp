package dev.pocket.mcp;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicVerificationTest {
    @Test public void recoversAfterTransientTransportFailure() {
        AtomicInteger calls=new AtomicInteger();
        assertTrue(PublicVerification.verify(auth->{if(calls.incrementAndGet()==1) throw new IOException("fake-secret");return auth?200:401;},()->false,attempt->{}));
        assertEquals(3,calls.get());
    }
    @Test public void rejectsUnprotectedPublicEndpointImmediately() {
        AtomicInteger calls=new AtomicInteger();
        assertFalse(PublicVerification.verify(auth->{calls.incrementAndGet();return 200;},()->false,attempt->fail("Must not retry an unprotected endpoint")));
        assertEquals(1,calls.get());
    }
    @Test public void rejectsWrongBearerImmediately() {
        AtomicInteger calls=new AtomicInteger();
        assertFalse(PublicVerification.verify(auth->{calls.incrementAndGet();return 401;},()->false,attempt->fail("Must not retry invalid authentication")));
        assertEquals(2,calls.get());
    }
    @Test public void attemptsAtMostThreeTransientProbes() {
        AtomicInteger calls=new AtomicInteger(),pauses=new AtomicInteger();
        assertFalse(PublicVerification.verify(auth->{calls.incrementAndGet();return 503;},()->false,attempt->pauses.incrementAndGet()));
        assertEquals(3,calls.get());assertEquals(2,pauses.get());
    }
    @Test public void stoppedConnectionDoesNotProbe() {
        assertFalse(PublicVerification.verify(auth->{fail("Stopped connection must not probe");return 200;},()->true,attempt->{}));
    }
    @Test public void stoppingBetweenProbesDoesNotSendBearer() {
        AtomicInteger calls=new AtomicInteger();
        assertFalse(PublicVerification.verify(auth->{calls.incrementAndGet();return 401;},()->calls.get()>0,attempt->{}));
        assertEquals(1,calls.get());
    }
}
