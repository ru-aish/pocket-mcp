package dev.pocket.control;

import org.junit.Test;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AgentSessionsTest {
    private static final class Rig {
        long now=1000;
        int next=2;
        final HashSet<Integer> active=new HashSet<>();
        final AgentSessions sessions=new AgentSessions(()->now,active::remove);
        int create() {int id=next++;if(!active.add(id)) throw new AssertionError();return id;}
    }
    @Test public void allocateThreeReleaseAndReuse() throws Exception {
        Rig r=new Rig();
        String a=r.sessions.create().token(),b=r.sessions.create().token(),c=r.sessions.create().token(),d=r.sessions.create().token();
        assertEquals(-1,r.sessions.status(a).displayId());
        int ia=r.sessions.acquire(a,r::create).displayId();
        int ib=r.sessions.acquire(b,r::create).displayId();
        int ic=r.sessions.acquire(c,r::create).displayId();
        assertEquals(3,r.active.size());
        assertEquals(ia,r.sessions.acquire(a,r::create).displayId());
        assertThrows(ControlException.class,()->r.sessions.acquire(d,r::create));
        r.sessions.close(b);
        assertFalse(r.active.contains(ib));
        assertTrue(r.active.contains(ia));
        assertTrue(r.active.contains(ic));
        int newId=r.sessions.acquire(d,r::create).displayId();
        assertTrue(newId>0);
        r.sessions.clear();
        assertTrue(r.active.isEmpty());
    }
    @Test public void forgedAndExpiredTokensNeverGetUi() throws Exception {
        Rig r=new Rig();String token=r.sessions.create().token();
        assertThrows(ControlException.class,()->r.sessions.checkedDisplay("forged"));
        assertThrows(ControlException.class,()->r.sessions.checkedDisplay(token));
        r.sessions.acquire(token,r::create);
        r.now+=AgentSessions.UI_IDLE_MILLIS+1;
        assertThrows(ControlException.class,()->r.sessions.checkedDisplay(token));
        assertTrue(r.active.isEmpty());
        r.now+=AgentSessions.SESSION_IDLE_MILLIS+1;
        assertThrows(ControlException.class,()->r.sessions.status(token));
    }
    @Test public void failedCreateLeavesNoLease() throws Exception {
        Rig r=new Rig();String token=r.sessions.create().token();
        assertThrows(ControlException.class,()->r.sessions.acquire(token,()->{throw new ControlException(ControlException.Code.NOT_READY,"shizuku disconnected");}));
        assertEquals(-1,r.sessions.status(token).displayId());
        assertTrue(r.active.isEmpty());
    }
    @Test public void concurrentRequestsNeverDuplicate() throws Exception {
        Rig r=new Rig();
        String[] ids=new String[4];for(int i=0;i<4;i++) ids[i]=r.sessions.create().token();
        CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(4);
        AtomicInteger successes=new AtomicInteger();
        for(String id:ids) new Thread(()->{try {start.await();r.sessions.acquire(id,r::create);successes.incrementAndGet();}catch(Exception expected){}finally {done.countDown();}}).start();
        start.countDown();done.await();
        assertEquals(3,successes.get());
        assertEquals(3,r.active.size());
    }
}
