package dev.pocket.mcp;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** In-memory, resource-bound OAuth grants. Stopping phone control revokes every grant. */
final class OAuthTokens {
    record Client(String id,String name,List<String> redirects) {}
    record Grant(String client,String redirect,String challenge,String resource,long expires) {}
    record TokenGrant(String client,String resource,long expires) {}
    record TokenSet(String access,String refresh,int expiresIn) {}

    private static final long CODE_TTL=300_000L,ACCESS_TTL=3_600_000L,REFRESH_TTL=30L*24*3_600_000L;
    private static final int MAX_CLIENTS=128,MAX_GRANTS=512;
    private final SecureRandom random=new SecureRandom();
    private final LongSupplier now;
    private final Map<String,Client> clients=new HashMap<>();
    private final Map<String,Grant> codes=new HashMap<>();
    private final Map<String,TokenGrant> access=new HashMap<>(),refresh=new HashMap<>();
    private String origin;

    OAuthTokens() {this(System::currentTimeMillis);}
    OAuthTokens(LongSupplier time) {now=time;}

    synchronized void setOrigin(String url) {
        String value=canonicalOrigin(url);
        if(!value.equals(origin)) {clients.clear();codes.clear();access.clear();refresh.clear();}
        origin=value;
    }

    synchronized String origin() {return origin;}
    synchronized String resource() {return origin==null?null:origin+"/mcp";}

    static String canonicalOrigin(String url) {
        try {
            URI parsed=URI.create(url);
            if(!"https".equals(parsed.getScheme()) || parsed.getHost()==null ||
                parsed.getUserInfo()!=null || parsed.getQuery()!=null || parsed.getFragment()!=null ||
                !(parsed.getPath().isEmpty() || parsed.getPath().equals("/"))) throw new IllegalArgumentException();
            return parsed.getScheme()+"://"+parsed.getRawAuthority();
        } catch(RuntimeException e) {throw new IllegalArgumentException("Invalid public HTTPS origin");}
    }

    static boolean safeRedirect(String uri) {
        try {
            URI u=URI.create(uri);
            if(u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null || u.getQuery()!=null) return false;
            return "https".equals(u.getScheme()) || ("http".equals(u.getScheme()) &&
                List.of("localhost","127.0.0.1","[::1]").contains(u.getHost()));
        } catch(RuntimeException e) {return false;}
    }

    private String secret() {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    synchronized Client register(String name,List<String> redirects) {
        if(origin==null) throw new IllegalStateException("Public connection unavailable");
        if(clients.size()>=MAX_CLIENTS) throw new IllegalStateException("Client limit reached");
        if(redirects.isEmpty() || redirects.size()>10 || redirects.stream().anyMatch(uri->uri.length()>2048 || !safeRedirect(uri)))
            throw new IllegalArgumentException("Invalid redirect URI");
        String id=secret();
        Client c=new Client(id,name.substring(0,Math.min(name.length(),128)),List.copyOf(redirects));
        clients.put(id,c);
        return c;
    }

    synchronized boolean authorizeRequest(String clientId,String redirect,String challenge,String method,String resource,String responseType) {
        Client c=clients.get(clientId);
        return origin!=null && c!=null && c.redirects().contains(redirect) &&
            resource().equals(resource) && "code".equals(responseType) && "S256".equals(method) &&
            challenge!=null && challenge.matches("[A-Za-z0-9_-]{43,128}");
    }

    synchronized String issueCode(String clientId,String redirect,String challenge,String resource) {
        if(!authorizeRequest(clientId,redirect,challenge,"S256",resource,"code")) throw new IllegalArgumentException("Invalid authorization");
        sweep();
        if(codes.size()>=MAX_GRANTS) throw new IllegalStateException("Too many pending authorizations");
        String code=secret();
        codes.put(code,new Grant(clientId,redirect,challenge,resource,now.getAsLong()+CODE_TTL));
        return code;
    }

    synchronized TokenSet exchange(String code,String clientId,String redirect,String verifier,String resource) {
        Grant g=codes.get(code);
        if(g==null || g.expires()<=now.getAsLong() || !g.client().equals(clientId) ||
            !g.redirect().equals(redirect) || !g.resource().equals(resource) ||
            !resource.equals(resource()) || verifier==null || !verifier.matches("[A-Za-z0-9._~-]{43,128}"))
            throw new IllegalArgumentException("Invalid authorization code");
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            if(!MessageDigest.isEqual(g.challenge().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    challenge.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException("Invalid PKCE verifier");
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        codes.remove(code);
        return mint(clientId,resource);
    }

    synchronized TokenSet rotate(String refreshToken,String clientId,String resource) {
        TokenGrant g=refresh.get(refreshToken);
        if(g==null || g.expires()<=now.getAsLong() || !g.client().equals(clientId) ||
            !g.resource().equals(resource) || !resource.equals(resource()))
            throw new IllegalArgumentException("Invalid refresh token");
        refresh.remove(refreshToken);
        return mint(clientId,resource);
    }

    synchronized boolean valid(String token) {
        if(token==null || token.isEmpty() || origin==null) return false;
        TokenGrant g=access.get(token);
        return g!=null && g.expires()>now.getAsLong() && resource().equals(g.resource());
    }

    synchronized boolean registered(String clientId) {return clients.containsKey(clientId);}

    private void sweep() {
        long time=now.getAsLong();
        codes.values().removeIf(g->g.expires()<=time);
        access.values().removeIf(g->g.expires()<=time);
        refresh.values().removeIf(g->g.expires()<=time);
    }

    private TokenSet mint(String clientId,String resource) {
        sweep();
        if(access.size()>=MAX_GRANTS || refresh.size()>=MAX_GRANTS) throw new IllegalStateException("Token limit reached");
        String a=secret(),r=secret();
        access.put(a,new TokenGrant(clientId,resource,now.getAsLong()+ACCESS_TTL));
        refresh.put(r,new TokenGrant(clientId,resource,now.getAsLong()+REFRESH_TTL));
        return new TokenSet(a,r,(int)(ACCESS_TTL/1000));
    }
}
