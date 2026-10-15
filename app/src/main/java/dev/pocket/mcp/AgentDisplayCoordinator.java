package dev.pocket.mcp;

import android.content.Context;
import android.os.SystemClock;
import dev.pocket.android.AndroidScreenBackend;
import dev.pocket.control.AgentSessions;
import dev.pocket.control.ControlException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

/** Exclusive UI leases for up to three external agents; the physical screen is never leased. */
final class AgentDisplayCoordinator implements AutoCloseable {
    private final AgentSessions sessions;
    private ShizukuDisplayClient client;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"pocket-display-expiry");t.setDaemon(true);return t;
    });
    AgentDisplayCoordinator(Context context) {
        sessions=new AgentSessions(SystemClock::elapsedRealtime,id->{
            PhoneObserver observer=PhoneObserver.connected;
            if(observer!=null) observer.release(id);
            if(client!=null) client.removeDisplay(id);
        });
        client=new ShizukuDisplayClient(context,sessions::clear);
        watchdog.scheduleAtFixedRate(sessions::expire,60,60,TimeUnit.SECONDS);
        client.tryBind();
    }
    private static JSONObject state(AgentSessions.State state,boolean includeToken) {
        JSONObject result=Json.obj("agent",state.token().substring(0,8),"has_ui",state.displayId()>0,
            "display_id",state.displayId()>0?state.displayId():JSONObject.NULL,
            "last_activity_ms",state.lastActivity());
        if(includeToken) try {result.put("session_id",state.token());}catch(Exception e){throw new IllegalStateException(e);}
        return result;
    }
    JSONObject create() {return state(sessions.create(),true);}
    JSONObject acquire(String token) throws ControlException {
        AgentSessions.State state=sessions.acquire(token,client::createDisplay);
        return state(state,true);
    }
    JSONObject release(String token) throws ControlException {return state(sessions.release(token),true);}
    JSONObject close(String token) throws ControlException {
        sessions.close(token);
        return Json.obj("status","closed");
    }
    JSONObject status(String token) throws ControlException {return state(sessions.status(token),true);}
    JSONObject list() {
        JSONArray all=new JSONArray();
        for(AgentSessions.State entry:sessions.list()) all.put(state(entry,false));
        return Json.obj("agents",all,"active_sessions",all.length(),
            "max_ui_agents",AgentSessions.MAX_DISPLAYS,"backend",client.status());
    }
    JSONObject displays() {
        JSONArray list=new JSONArray();
        list.put(Json.obj("display_id",0,"role","physical","available_to_agents",false));
        for(AgentSessions.State entry:sessions.list()) {
            if(entry.displayId()>0) list.put(Json.obj("display_id",entry.displayId(),
                "role","virtual","agent",entry.token().substring(0,8),"status","leased"));
        }
        return Json.obj("displays",list,"backend",client.status(),"on_demand",true);
    }
    boolean sessionsExist() {return !sessions.list().isEmpty();}
    int display(String token) throws ControlException {
        int id=sessions.checkedDisplay(token);
        if(!"ready".equals(client.status()))
            throw new ControlException(NOT_READY,"Shizuku backend disconnected; virtual UI not accessible");
        return id;
    }
    AndroidScreenBackend screen(String token) throws ControlException {
        int id=display(token);
        PhoneObserver observer=PhoneObserver.connected;
        if(observer==null) throw new ControlException(PERMISSION_REQUIRED,"Enable accessibility to observe the leased display");
        return observer.backend(id);
    }
    void launch(String token,String component) throws ControlException {client.launch(display(token),component);}
    void key(String token,int code) throws ControlException {client.key(display(token),code);}
    String backendStatus() {return client.status();}
    void prepare() {client.tryBind();}
    @Override public void close() {
        watchdog.shutdownNow();
        sessions.clear();
        client.close();
    }
}
