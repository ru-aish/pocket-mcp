package dev.pocket.mcp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class OAuthTokensTest {
    private static final String CALLBACK="https://chatgpt.com/connector/oauth/pocket-test";
    private static final String VERIFIER="this-is-a-valid-pkce-verifier-long-enough-to-use-here";
    private static String challenge() throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }
    private static void rejects(Runnable run) {
        try {run.run();fail("Expected rejection");}
        catch(IllegalArgumentException expected) {}
    }

    @Test public void registrationPkceRotationResourceAndReplay() throws Exception {
        AtomicLong now=new AtomicLong(1_000_000L);
        OAuthTokens tokens=new OAuthTokens(now::get);
        tokens.setOrigin("https://pocket.ngrok-free.app/");
        String resource=tokens.resource();
        assertEquals("https://pocket.ngrok-free.app/mcp",resource);
        OAuthTokens.Client client=tokens.register("ChatGPT",List.of(CALLBACK));
        assertFalse(tokens.authorizeRequest(client.id(),CALLBACK,"no","S256",resource,"code"));
        assertFalse(tokens.authorizeRequest(client.id(),CALLBACK,challenge(),"plain",resource,"code"));
        assertFalse(tokens.authorizeRequest(client.id(),CALLBACK,challenge(),"S256","https://evil.example/mcp","code"));
        String code=tokens.issueCode(client.id(),CALLBACK,challenge(),resource);
        rejects(()->tokens.exchange(code,client.id(),CALLBACK,"bad-verifier","https://evil.example/mcp"));
        OAuthTokens.TokenSet first=tokens.exchange(code,client.id(),CALLBACK,VERIFIER,resource);
        assertTrue(tokens.valid(first.access()));
        rejects(()->tokens.exchange(code,client.id(),CALLBACK,VERIFIER,resource));
        rejects(()->tokens.rotate(first.refresh(),client.id(),"https://evil.example/mcp"));
        OAuthTokens.TokenSet second=tokens.rotate(first.refresh(),client.id(),resource);
        rejects(()->tokens.rotate(first.refresh(),client.id(),resource));
        assertTrue(tokens.valid(second.access()));
        now.addAndGet(3_600_001L);
        assertFalse(tokens.valid(second.access()));
        OAuthTokens.TokenSet third=tokens.rotate(second.refresh(),client.id(),resource);
        assertTrue(tokens.valid(third.access()));
        tokens.setOrigin("https://new.ngrok-free.app");
        assertFalse(tokens.valid(third.access()));
        rejects(()->tokens.rotate(third.refresh(),client.id(),resource));
    }

    @Test public void rejectsUnsafeRedirectsAndExpiredCodes() throws Exception {
        AtomicLong now=new AtomicLong(1L);
        OAuthTokens tokens=new OAuthTokens(now::get);
        tokens.setOrigin("https://pocket.ngrok-free.app");
        for(String uri:List.of("javascript:alert(1)","http://evil.example/callback",
                "https://example.com/cb#fragment","https://user:pass@example.com/cb")) {
            rejects(()->tokens.register("bad",List.of(uri)));
        }
        OAuthTokens.Client client=tokens.register("valid",List.of(CALLBACK));
        String code=tokens.issueCode(client.id(),CALLBACK,challenge(),tokens.resource());
        now.addAndGet(301_000L);
        rejects(()->tokens.exchange(code,client.id(),CALLBACK,VERIFIER,tokens.resource()));
        rejects(()->OAuthTokens.canonicalOrigin("http://example.com"));
        rejects(()->OAuthTokens.canonicalOrigin("https://example.com/evil"));
    }
}
