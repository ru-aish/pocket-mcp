package dev.pocket.mcp;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OAuth endpoints on the same HTTPS-tunneled Android MCP listener. */
final class OAuthHttp {
    private final OAuthTokens grants=new OAuthTokens();
    private final byte[] loginPassword;
    private int failedLogins;
    private long failureWindow;

    OAuthHttp(String password) {
        loginPassword=password.getBytes(StandardCharsets.UTF_8);
    }
    void setPublicOrigin(String url) {grants.setOrigin(url);}
    boolean valid(String token) {return grants.valid(token);}
    String challenge() {
        String origin=grants.origin();
        return origin==null?"Bearer": "Bearer resource_metadata=\""+origin+"/.well-known/oauth-protected-resource\", scope=\"mcp\"";
    }

    Response handle(IHTTPSession s) {
        String path=s.getUri();
        boolean metadata=path.equals("/.well-known/oauth-authorization-server") ||
            path.equals("/.well-known/oauth-protected-resource") ||
            path.equals("/.well-known/oauth-protected-resource/mcp");
        if(!metadata && !path.equals("/register") && !path.equals("/authorize") && !path.equals("/token")) return null;
        String origin=grants.origin();
        if(origin==null) return json(Response.Status.SERVICE_UNAVAILABLE,Json.obj("error","connection_unavailable"));
        if(s.getHeaders().containsKey("origin") && !origin.equals(s.getHeaders().get("origin")))
            return json(Response.Status.FORBIDDEN,Json.obj("error","browser_origin_forbidden"));
        if(metadata) {
            if(s.getMethod()!=Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED,Json.obj("error","method_not_allowed"));
            if(path.equals("/.well-known/oauth-authorization-server")) return json(Response.Status.OK,Json.obj(
                "issuer",origin,"authorization_endpoint",origin+"/authorize",
                "token_endpoint",origin+"/token","registration_endpoint",origin+"/register",
                "response_types_supported",Json.arr("code"),
                "grant_types_supported",Json.arr("authorization_code","refresh_token"),
                "code_challenge_methods_supported",Json.arr("S256"),
                "token_endpoint_auth_methods_supported",Json.arr("none"),
                "scopes_supported",Json.arr("mcp","offline_access")
            ));
            return json(Response.Status.OK,Json.obj(
                "resource",grants.resource(),"authorization_servers",Json.arr(origin),
                "bearer_methods_supported",Json.arr("header"),"scopes_supported",Json.arr("mcp","offline_access")
            ));
        }
        try {
            if(path.equals("/register")) {
                if(s.getMethod()!=Method.POST) return invalid("method_not_allowed",Response.Status.METHOD_NOT_ALLOWED);
                if(!isContentType(s,"application/json")) return invalid("invalid_client_metadata",Response.Status.BAD_REQUEST);
                JSONObject payload=new JSONObject(readBody(s));
                JSONArray redirect=payload.getJSONArray("redirect_uris");
                List<String> uris=new ArrayList<>();
                if(redirect.length()>10) return invalid("invalid_redirect_uri",Response.Status.BAD_REQUEST);
                for(int i=0;i<redirect.length();i++) uris.add(redirect.getString(i));
                OAuthTokens.Client c=grants.register(payload.optString("client_name","Pocket MCP client"),uris);
                return json(Response.Status.CREATED,Json.obj(
                    "client_id",c.id(),"client_name",c.name(),"redirect_uris",new JSONArray(c.redirects()),
                    "token_endpoint_auth_method","none","grant_types",Json.arr("authorization_code","refresh_token"),
                    "response_types",Json.arr("code")
                ));
            }
            if(path.equals("/authorize")) {
                if(s.getMethod()!=Method.GET && s.getMethod()!=Method.POST) return invalid("method_not_allowed",Response.Status.METHOD_NOT_ALLOWED);
                Map<String,String> params=s.getMethod()==Method.GET?s.getParms():parseForm(s);
                String client=params.getOrDefault("client_id","");
                String redirect=params.getOrDefault("redirect_uri","");
                String challenge=params.getOrDefault("code_challenge","");
                String resource=params.getOrDefault("resource","");
                if(!grants.authorizeRequest(client,redirect,challenge,params.get("code_challenge_method"),resource,params.get("response_type")) ||
                    !allowedScopes(params.getOrDefault("scope","mcp")))
                    return invalid("invalid_request",Response.Status.BAD_REQUEST);
                if(s.getMethod()==Method.GET) return login(params,false);
                if(!checkPassword(params.getOrDefault("password",""))) return login(params,true);
                String code=grants.issueCode(client,redirect,challenge,resource);
                Map<String,String> query=new LinkedHashMap<>();
                query.put("code",code);query.put("state",params.getOrDefault("state",""));
                String url=redirect+(redirect.contains("?")?"&":"?")+encode(query);
                Response r=NanoHTTPD.newFixedLengthResponse(Response.Status.REDIRECT,"text/plain; charset=utf-8","");
                r.addHeader("Location",url);
                r.addHeader("Cache-Control","no-store");
                return r;
            }
            if(s.getMethod()!=Method.POST) return invalid("method_not_allowed",Response.Status.METHOD_NOT_ALLOWED);
            Map<String,String> params=parseForm(s);
            String client=params.getOrDefault("client_id","");
            String resource=params.getOrDefault("resource","");
            if(!grants.registered(client) || !grants.resource().equals(resource)) return invalid("invalid_grant",Response.Status.BAD_REQUEST);
            OAuthTokens.TokenSet tokens;
            switch(params.getOrDefault("grant_type","")) {
                case "authorization_code" -> tokens=grants.exchange(
                    params.getOrDefault("code",""),client,params.getOrDefault("redirect_uri",""),
                    params.getOrDefault("code_verifier",""),resource);
                case "refresh_token" -> tokens=grants.rotate(params.getOrDefault("refresh_token",""),client,resource);
                default -> {return invalid("unsupported_grant_type",Response.Status.BAD_REQUEST);}
            }
            return json(Response.Status.OK,Json.obj(
                "access_token",tokens.access(),"token_type","Bearer","expires_in",tokens.expiresIn(),
                "refresh_token",tokens.refresh(),"scope","mcp offline_access"
            ));
        } catch(Exception e) {
            return invalid(path.equals("/register")?"invalid_client_metadata":
                path.equals("/authorize")?"invalid_request":"invalid_grant",Response.Status.BAD_REQUEST);
        }
    }

    private synchronized boolean checkPassword(String value) {
        long now=System.currentTimeMillis();
        if(now-failureWindow>60_000) {failureWindow=now;failedLogins=0;}
        if(failedLogins>=12) return false;
        boolean valid=MessageDigest.isEqual(loginPassword,value.getBytes(StandardCharsets.UTF_8));
        if(!valid) failedLogins++;
        else failedLogins=0;
        return valid;
    }

    private static boolean allowedScopes(String scopes) {
        for(String scope:scopes.split(" ")) if(!scope.isBlank() && !scope.equals("mcp") && !scope.equals("offline_access")) return false;
        return true;
    }

    private Response login(Map<String,String> params,boolean invalid) {
        StringBuilder hidden=new StringBuilder();
        for(String key:List.of("client_id","response_type","redirect_uri","state","code_challenge","code_challenge_method","scope","resource")) {
            if(params.containsKey(key)) hidden.append("<input type='hidden' name='").append(key).append("' value='")
                .append(escape(params.get(key))).append("'>");
        }
        String page="<!doctype html><html lang='en'><meta name='viewport' content='width=device-width'>"+
            "<meta charset='utf-8'><title>Authorize Pocket MCP</title>"+
            "<style>body{font:16px system-ui;max-width:35rem;margin:3rem auto;padding:1rem}input{display:block;margin:.75rem 0;padding:.65rem;width:95%}</style>"+
            "<h1>Authorize Pocket MCP</h1><p>This client will be able to control your Android phone, "+
            "including sending messages and placing calls. Only connect trusted clients.</p>"+
            (invalid?"<p role='alert'>Invalid password or too many attempts.</p>":"")+
            "<form action='/authorize' method='post'>"+hidden+
            "<label>MCP access password<input type='password' name='password' autocomplete='off' required></label>"+
            "<button type='submit'>Authorize</button></form></html>";
        Response r=NanoHTTPD.newFixedLengthResponse(invalid?Response.Status.UNAUTHORIZED:Response.Status.OK,"text/html; charset=utf-8",page);
        r.addHeader("Cache-Control","no-store");
        r.addHeader("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        r.addHeader("X-Frame-Options","DENY");
        return r;
    }

    private static String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("'","&#39;");
    }
    private static String encode(Map<String,String> params) {
        StringBuilder result=new StringBuilder();
        for(var entry:params.entrySet()) {
            if(result.length()>0) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(entry.getValue(),StandardCharsets.UTF_8));
        }
        return result.toString();
    }
    private static boolean isContentType(IHTTPSession s,String type) {
        return s.getHeaders().getOrDefault("content-type","").split(";",2)[0].trim().equalsIgnoreCase(type);
    }
    private static String readBody(IHTTPSession s) throws Exception {
        int size=Integer.parseInt(s.getHeaders().getOrDefault("content-length","-1"));
        if(size<1 || size>16384) throw new IllegalArgumentException("Oversized request");
        byte[] bytes=new byte[size];int offset=0;
        while(offset<size) {int count=s.getInputStream().read(bytes,offset,size-offset);if(count<0) throw new java.io.EOFException();offset+=count;}
        return new String(bytes,StandardCharsets.UTF_8);
    }
    private static Map<String,String> parseForm(IHTTPSession s) throws Exception {
        if(!isContentType(s,"application/x-www-form-urlencoded")) throw new IllegalArgumentException("Expected form data");
        Map<String,String> params=new LinkedHashMap<>();
        for(String part:readBody(s).split("&")) {
            String[] pair=part.split("=",2);
            String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            if(params.containsKey(key)) throw new IllegalArgumentException("Duplicate parameter");
            params.put(key,URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8));
        }
        return params;
    }
    private static Response invalid(String error,Response.IStatus status) {
        return json(status,Json.obj("error",error));
    }
    private static Response json(Response.IStatus status,JSONObject body) {
        Response r=NanoHTTPD.newFixedLengthResponse(status,"application/json; charset=utf-8",body.toString());
        r.addHeader("Cache-Control","no-store");r.addHeader("Pragma","no-cache");
        r.addHeader("X-Content-Type-Options","nosniff");return r;
    }
}
