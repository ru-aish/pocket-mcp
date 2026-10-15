package dev.pocket.control;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session ownership is checked inside the service, never inferred from MCP client names. */
public final class AgentSessions {
    public static final int MAX_DISPLAYS = 3;
    public static final int MAX_SESSIONS = 128;
    public static final long SESSION_IDLE_MILLIS = 30 * 60 * 1000L;
    public static final long UI_IDLE_MILLIS = 10 * 60 * 1000L;
    public interface Clock { long millis(); }
    public interface DisplayFactory { int create() throws ControlException; }
    public interface DisplayReleaser { void release(int id); }
    public record State(String token, int displayId, long lastActivity, long createdAt) {}
    private static final class Entry {
        final String token;
        final long createdAt;
        long lastActivity;
        long lastUiActivity;
        int displayId=-1;
        Entry(String token,long now) { this.token=token;createdAt=now;lastActivity=now;lastUiActivity=now; }
        State state() {return new State(token,displayId,lastActivity,createdAt);}
    }
    private final Map<String,Entry> sessions = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final DisplayReleaser releaser;
    public AgentSessions(Clock clock,DisplayReleaser releaser) {this.clock=clock;this.releaser=releaser;}
    public synchronized State create() throws ControlException {
        expire();
        if(sessions.size()>=MAX_SESSIONS) throw new ControlException(ControlException.Code.NOT_READY,"Agent session limit reached; close inactive sessions");
        byte[] nonce=new byte[24];
        String token;
        do {random.nextBytes(nonce);token=Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);}
        while(sessions.containsKey(token));
        Entry entry=new Entry(token,clock.millis());
        sessions.put(token,entry);
        return entry.state();
    }
    private Entry require(String token) throws ControlException {
        expire();
        Entry entry=sessions.get(token);
        if(entry==null) throw new ControlException(ControlException.Code.NOT_READY,"Agent session missing/expired; create a new session");
        entry.lastActivity=clock.millis();
        return entry;
    }
    public synchronized State status(String token) throws ControlException {return require(token).state();}
    public synchronized State acquire(String token,DisplayFactory factory) throws ControlException {
        Entry entry=require(token);
        if(entry.displayId!=-1) {entry.lastUiActivity=clock.millis();return entry.state();}
        int active=0;
        for(Entry e:sessions.values()) if(e.displayId!=-1) active++;
        if(active>=MAX_DISPLAYS) throw new ControlException(ControlException.Code.NOT_READY,"All three agent displays are leased; release one before acquiring UI");
        int id=factory.create();
        if(id<=0 || sessions.values().stream().anyMatch(e->e.displayId==id)) {
            // A duplicated ID belongs to another lease: never release that display.
            throw new ControlException(ControlException.Code.NOT_READY,"Display backend returned an invalid or duplicate ID");
        }
        entry.displayId=id;entry.lastUiActivity=clock.millis();return entry.state();
    }
    public synchronized int checkedDisplay(String token) throws ControlException {
        Entry entry=require(token);
        if(entry.displayId<=0) throw new ControlException(ControlException.Code.NOT_READY,"Agent has no UI lease; call agent_session_acquire_ui");
        entry.lastUiActivity=clock.millis();
        return entry.displayId;
    }
    public synchronized State release(String token) throws ControlException {
        Entry entry=require(token);
        releaseEntry(entry);
        return entry.state();
    }
    private void releaseEntry(Entry entry) {
        if(entry.displayId>0) {int id=entry.displayId;entry.displayId=-1;releaser.release(id);}
    }
    public synchronized void close(String token) throws ControlException {
        Entry entry=require(token);
        sessions.remove(token);
        releaseEntry(entry);
    }
    public synchronized List<State> list() {
        expire();
        List<State> out=new ArrayList<>();
        for(Entry e:sessions.values()) out.add(e.state());
        return out;
    }
    public synchronized void expire() {
        long now=clock.millis();
        var it=sessions.values().iterator();
        while(it.hasNext()) {
            Entry e=it.next();
            if(now-e.lastActivity>=SESSION_IDLE_MILLIS) {it.remove();releaseEntry(e);}
            else if(e.displayId>0 && now-e.lastUiActivity>=UI_IDLE_MILLIS) releaseEntry(e);
        }
    }
    /** A privileged UI service death invalidates only virtual leases, not native-only agents. */
    public synchronized void invalidateDisplays() {
        for(Entry e:sessions.values()) releaseEntry(e);
    }
    public synchronized void clear() {
        invalidateDisplays();
        sessions.clear();
    }
}
