package dev.pocket.mcp;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class TunnelFailureTest {
    @Test public void keepsOnlyNumericNgrokCode() {
        String secret="fake_secret_that_must_never_escape";
        String result=TunnelFailure.from(new IOException("token="+secret+" ERR_NGROK_108 account=user@example.com")).summary();
        assertTrue(result.contains("ERR_NGROK_108"));
        assertFalse(result.contains(secret));assertFalse(result.contains("user@example.com"));
    }
    @Test public void identifiesNestedSdkMismatch() {
        assertEquals("sdk-class-mismatch",TunnelFailure.from(new RuntimeException(new NoSuchMethodError("secret"))).kind);
    }
    @Test public void unknownErrorsNeverExposeTheirMessage() {
        assertEquals("Diagnostic: unknown",TunnelFailure.from(new IllegalArgumentException("secret ERR_NGROK_secret")).summary());
    }
    @Test public void dnsHasItsOwnClassification() {
        assertEquals("dns",TunnelFailure.from(new java.net.UnknownHostException("secret-host")).kind);
    }
    @Test public void sessionLimitHasSpecificRecoveryAdvice() {
        assertTrue(TunnelFailure.from(new IOException("ERR_NGROK_108 secret")).advice().contains("agent-session limit"));
    }
    @Test public void revokedTokenHasSpecificRecoveryAdvice() {
        assertTrue(TunnelFailure.from(new IOException("ERR_NGROK_107 secret")).advice().contains("authtoken"));
    }
}
