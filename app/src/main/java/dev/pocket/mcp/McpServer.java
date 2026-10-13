package dev.pocket.mcp;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Stateless Streamable HTTP with bounded payloads and serialized device actions. */
final class McpServer extends NanoHTTPD {
    private static final int MAX_BODY=1_048_576;
    private final byte[] authorization;
    private final PhoneTools tools;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private volatile boolean closing;
    McpServer(Context context,String bearer) throws Exception {
        super("127.0.0.1",8765);authorization=("Bearer "+bearer).getBytes(StandardCharsets.UTF_8);tools=new PhoneTools(context);
    }
    private Response response(Response.IStatus code,String body) {Response r=newFixedLengthResponse(code,"application/json; charset=utf-8",body);r.addHeader("Cache-Control","no-store");r.addHeader("X-Content-Type-Options","nosniff");return r;}
    private Response error(Response.IStatus status,int code,String message,Object id) {return response(status,Json.obj("jsonrpc","2.0","id",id,"error",Json.obj("code",code,"message",message)).toString());}
    @Override public Response serve(IHTTPSession session) {
        String auth=session.getHeaders().get("authorization");
        if(auth==null || !MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.UTF_8))) {Response r=error(Response.Status.UNAUTHORIZED,-32001,"Bearer authorization required",null);r.addHeader("WWW-Authenticate","Bearer");return r;}
        // Native agent clients send no Origin. Browser access requires an explicit future allowlist.
        if(session.getHeaders().containsKey("origin")) return error(Response.Status.FORBIDDEN,-32002,"Browser origins are not enabled",null);
        if(!"/mcp".equals(session.getUri())) return error(Response.Status.NOT_FOUND,-32601,"Unknown endpoint",null);
        if(session.getMethod()!=Method.POST) {Response r=error(Response.Status.METHOD_NOT_ALLOWED,-32600,"Use POST; this stateless server has no event stream",null);r.addHeader("Allow","POST");return r;}
        String protocol=session.getHeaders().get("mcp-protocol-version");if(protocol!=null && !supported(protocol)) return error(Response.Status.BAD_REQUEST,-32600,"Unsupported protocol version",null);
        String type=session.getHeaders().get("content-type");if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json")) return error(Response.Status.UNSUPPORTED_MEDIA_TYPE,-32600,"Use application/json",null);
        Object id=null;JSONObject request;
        try {
            int length=Integer.parseInt(session.getHeaders().getOrDefault("content-length","-1"));
            if(length<1 || length>MAX_BODY) return error(Response.Status.BAD_REQUEST,-32600,"Content-Length must be 1..1048576",null);
            byte[] data=new byte[length];int offset=0;while(offset<length) {int n=session.getInputStream().read(data,offset,length-offset);if(n<0) throw new java.io.EOFException();offset+=n;}
            request=new JSONObject(new String(data,StandardCharsets.UTF_8));if(!"2.0".equals(request.optString("jsonrpc"))) throw new IllegalArgumentException();
            if(request.has("id")) {id=request.get("id");if(!(id instanceof String || id instanceof Integer || id instanceof Long)) throw new IllegalArgumentException();}
            if(!request.has("method")) {if(request.has("result") || request.has("error")) return response(Response.Status.ACCEPTED,"");throw new IllegalArgumentException();}
            if(!(request.get("method") instanceof String)) throw new IllegalArgumentException();
        } catch(Exception e) {Response r=error(Response.Status.BAD_REQUEST,-32700,"Invalid JSON-RPC request",id);r.addHeader("Connection","close");return r;}
        String method=request.optString("method");
        if(!request.has("id")) {
            if(method.startsWith("notifications/")) return response(Response.Status.ACCEPTED,"");
            return error(Response.Status.BAD_REQUEST,-32600,"Requests require an id",null);
        }
        JSONObject params=request.optJSONObject("params");if(params==null) params=new JSONObject();JSONObject result;
        try {
            switch(method) {
                case "initialize": {String requested=params.optString("protocolVersion","2025-11-25");result=Json.obj("protocolVersion",supported(requested)?requested:"2025-11-25","capabilities",Json.obj("tools",Json.obj("listChanged",false)),"serverInfo",Json.obj("name","pocket-mcp","version",BuildConfig.VERSION_NAME),"instructions","Prefer Android APIs. Get operation_prefix from phone_capabilities. Mutations require operation_id = operation_prefix + underscore + a unique nonce (8..48 letters/digits/dashes/underscores). Reuse the same ID and arguments after network loss. IDs from the current/previous UTC day are valid; expired IDs are never dispatched again. Inspect phone_operation for unknown results. Observe before every screen action; use full device coordinates. Do not report requested/dispatched as task completion.");break;}
                case "ping":result=new JSONObject();break;
                case "tools/list":result=Json.obj("tools",tools.list());break;
                case "resources/list":result=Json.obj("resources",Json.arr());break;
                case "prompts/list":result=Json.obj("prompts",Json.arr());break;
                case "tools/call": {
                    if(closing) return error(Response.Status.SERVICE_UNAVAILABLE,-32000,"Server stopping",id);
                    String name=params.getString("name");JSONObject args=params.optJSONObject("arguments");if(args==null) args=new JSONObject();JSONObject input=args;
                    Future<JSONObject> job;
                    try {job=worker.submit(()->closing?Json.failure("NOT_READY","Server stopping"):tools.call(name,input));}
                    catch(RejectedExecutionException e) {result=Json.failure("BUSY","Device action queue full; no action queued");break;}
                    try {result=job.get(30,TimeUnit.SECONDS);}
                    catch(TimeoutException e) {job.cancel(false);result=Json.failure("RESULT_UNKNOWN","Timed out; inspect operation_id/current state before repeating");}
                    catch(InterruptedException e) {job.cancel(false);Thread.currentThread().interrupt();result=Json.failure("RESULT_UNKNOWN","Interrupted; inspect operation before repeating");}
                    break;
                }
                default:return error(Response.Status.OK,-32601,"Unknown method",id);
            }
        } catch(Exception e) {return error(Response.Status.OK,-32602,"Invalid parameters or unavailable operation",id);}
        return response(Response.Status.OK,Json.obj("jsonrpc","2.0","id",id,"result",result).toString());
    }
    private static boolean supported(String p) {return p.equals("2025-11-25") || p.equals("2025-06-18") || p.equals("2025-03-26");}
    @Override public void stop() {closing=true;super.stop();worker.shutdownNow();}
}
