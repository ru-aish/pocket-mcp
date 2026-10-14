package dev.pocket.mcp;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.content.pm.PackageManager;
import com.ngrok.Session;
import com.ngrok.Forwarder;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ServerService extends android.app.Service {
    static volatile boolean nativeLoaded;
    private static volatile String stage="stopped", publicUrl="", diagnostic="";
    private static volatile boolean running, verified;
    private volatile boolean stopped;
    private volatile McpServer server;
    private volatile Session session;
    private volatile Forwarder.Endpoint endpoint;
    private PowerManager.WakeLock wake;
    private Thread starter;
    private final Handler lifetime=new Handler(Looper.getMainLooper());
    private final Runnable renewWake=new Runnable() {public void run(){if(stopped || wake==null) return;wake.acquire(20*60*1000L);lifetime.postDelayed(this,15*60*1000L);}};
    static JSONObject status() {return Json.obj("running",running,"stage",stage,"public_url",publicUrl,"public_https_verified",verified,"diagnostic",diagnostic);}
    @Override public void onCreate() {
        super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Phone control connection",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) {new Secrets(this).enabled(false);stopSelf();return START_NOT_STICKY;}
        Secrets secrets=new Secrets(this);
        if(intent==null && !secrets.enabled()) {stopSelf();return START_NOT_STICKY;}
        boolean local=intent!=null && "LOCAL".equals(intent.getAction());
        startForeground(1,notification("Starting phone connection"));
        if(starter!=null) return START_STICKY;
        stopped=false;stage="starting";diagnostic="";publicUrl="";verified=false;
        secrets.enabled(!local);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketMCP:Connection");wake.setReferenceCounted(false);renewWake.run();
        starter=new Thread(()->start(local,secrets),"pocket-tunnel");starter.start();
        return local?START_NOT_STICKY:START_STICKY;
    }
    private void start(boolean local,Secrets secrets) {
        try {
            stage="credentials";String bearer=secrets.bearer();String authtoken=local?"":secrets.ngrok();
            if(!local && authtoken.trim().isEmpty()) throw new IllegalArgumentException("MISSING_TOKEN");
            if(stopped) return;
            stage="local-server";McpServer created=new McpServer(this,bearer);created.start(10_000,false);
            synchronized(this) {if(stopped) {created.stop();return;}server=created;running=true;}
            stage="native-load";System.loadLibrary("ngrok_java");Class.forName("com.ngrok.NativeSession");nativeLoaded=true;
            synchronized(this) {if(stopped) return;if(local) {stage="local-only";update("Local connection active");return;}stage="ngrok-connect";}
            Session connected=Session.withAuthtoken(authtoken).addClientInfo("pocket-mcp",BuildConfig.VERSION_NAME).connect();
            synchronized(this) {if(stopped) {connected.close();return;}session=connected;}
            stage="endpoint-forward";com.ngrok.HttpBuilder builder=connected.httpEndpoint().scheme(com.ngrok.Http.Scheme.HTTPS);String domain=secrets.read("domain");if(!domain.isEmpty()) builder.domain(domain);
            Forwarder.Endpoint forwarder=builder.forward(new URL("http://127.0.0.1:8765"));
            synchronized(this) {if(stopped) {forwarder.close();return;}endpoint=forwarder;if(!"https".equals(new URL(forwarder.getUrl()).getProtocol())) throw new IllegalStateException("HTTPS endpoint required");created.setPublicOrigin(forwarder.getUrl());publicUrl=forwarder.getUrl().replaceAll("/$","")+"/mcp";}
            stage="verifying-https";boolean ok=checkPublic(publicUrl,bearer);
            synchronized(this) {if(stopped) return;verified=ok;stage=ok?"online":"https-unverified";if(!ok) diagnostic="Tunnel started; HTTPS check failed. Check network/account settings and retry.";}
            update(ok?"Public HTTPS connection verified":"Tunnel active; public HTTPS unverified");
        } catch(Throwable e) {
            if(stopped) return;
            diagnostic=switch(stage) {case "credentials" -> "Save your ngrok authtoken before starting the public connection.";case "native-load" -> "Tunnel library failed to load on this device.";case "ngrok-connect" -> "ngrok connection failed. Check your token, account limits and internet connection.";case "endpoint-forward" -> "Public endpoint could not start. Check the domain and account limits.";default -> "Connection could not start at "+stage+". Stop and try again.";};
            stage="failed";running=false;verified=false;publicUrl="";
            new Secrets(this).enabled(false);
            new Handler(Looper.getMainLooper()).post(this::stopSelf);
            // SDK messages can contain credentials. Do not log exceptions or include them in UI.
        }
    }
    private static int check(String url,String token,boolean authenticated) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();
        try {
            connection.setConnectTimeout(10_000);connection.setReadTimeout(10_000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json, text/event-stream");connection.setRequestProperty("ngrok-skip-browser-warning","true");connection.setRequestProperty("MCP-Protocol-Version","2025-11-25");
            if(authenticated) connection.setRequestProperty("Authorization","Bearer "+token);
            byte[] body=Json.obj("jsonrpc","2.0","id",1,"method","ping").toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);try(java.io.OutputStream out=connection.getOutputStream()) {out.write(body);}
            int code=connection.getResponseCode();
            if(authenticated && code==200) {java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=connection.getInputStream()) {byte[] chunk=new byte[1024];int n;while((n=in.read(chunk))!=-1) {bytes.write(chunk,0,n);if(bytes.size()>8192) return -1;}}JSONObject reply=new JSONObject(bytes.toString("UTF-8"));if(!reply.has("result") || reply.optInt("id")!=1) return -1;}
            return code;
        } finally {connection.disconnect();}
    }
    private static boolean checkPublic(String url,String bearer) {try {return check(url,bearer,false)==401 && check(url,bearer,true)==200;}catch(Exception e) {return false;}}
    private Notification notification(String message) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ServerService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"connection").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Pocket MCP").setContentText(message).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }
    private void update(String text) {if(Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) getSystemService(NotificationManager.class).notify(1,notification(text));}
    @Override public void onDestroy() {
        synchronized(this) {stopped=true;running=false;verified=false;publicUrl="";if(!"failed".equals(stage)) stage="stopped";if(server!=null) {server.stop();server=null;}}
        Thread cleanup=new Thread(()->{try {if(endpoint!=null) endpoint.close();}catch(Exception ignored){}try {if(session!=null) session.close();}catch(Exception ignored){}},"pocket-stop");cleanup.start();
        lifetime.removeCallbacks(renewWake);if(wake!=null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
