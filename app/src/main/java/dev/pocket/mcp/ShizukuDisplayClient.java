package dev.pocket.mcp;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import dev.pocket.control.ControlException;
import rikka.shizuku.Shizuku;

/** Single Shizuku session shared by all agent-owned virtual displays. */
final class ShizukuDisplayClient implements AutoCloseable {
    private final Shizuku.UserServiceArgs args;
    private final Runnable disconnected;
    private volatile IDisplayShell remote;
    private volatile boolean binding;
    private volatile boolean closed;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            IDisplayShell shell=IDisplayShell.Stub.asInterface(binder);
            try {binder.linkToDeath(ShizukuDisplayClient.this::loseService,0);}
            catch(RemoteException e) {loseService();return;}
            remote=shell;binding=false;
        }
        @Override public void onServiceDisconnected(ComponentName name) {loseService();}
    };
    private final Shizuku.OnBinderReceivedListener received=this::tryBind;
    private final Shizuku.OnBinderDeadListener died=this::loseService;

    ShizukuDisplayClient(Context context,Runnable disconnected) {
        this.disconnected=disconnected;
        args=new Shizuku.UserServiceArgs(new ComponentName(context,ShellDisplayService.class))
            .daemon(false).tag("pocket-agent-displays").processNameSuffix("display").version(1);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
    }
    private void loseService() {
        remote=null;binding=false;
        disconnected.run();
    }
    private boolean available() {
        try {return Shizuku.pingBinder();}catch(Throwable e){return false;}
    }
    String status() {
        if(closed) return "stopped";
        if(!available()) return "shizuku_unavailable";
        try {if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) return "permission_required";}
        catch(Throwable e) {return "shizuku_unavailable";}
        if(remote!=null && remote.asBinder().isBinderAlive()) return "ready";
        return binding?"binding":"disconnected";
    }
    synchronized void tryBind() {
        if(closed || binding || remote!=null || !available()) return;
        try {
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) return;
            binding=true;
            Shizuku.bindUserService(args,connection);
        } catch(Exception e) {binding=false;}
    }
    private IDisplayShell require() throws ControlException {
        String status=status();
        if(!"ready".equals(status)) {
            tryBind();
            throw new ControlException(ControlException.Code.NOT_READY,
                "Virtual display backend "+status+"; start Shizuku and authorize Pocket MCP in its UI, then retry");
        }
        return remote;
    }
    int createDisplay() throws ControlException {
        try {return require().createDisplay(720,1280,320);}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Shizuku display service disconnected");}
        catch(RuntimeException e) {throw new ControlException(ControlException.Code.NOT_READY,"Android rejected trusted display creation: "+e.getClass().getSimpleName());}
    }
    void removeDisplay(int displayId) {
        IDisplayShell service=remote;
        if(service!=null) try {service.removeDisplay(displayId);}catch(Exception ignored){}
    }
    void launch(int displayId,String componentName) throws ControlException {
        try {if(!require().launchActivity(displayId,componentName))
            throw new ControlException(ControlException.Code.ACTION_REJECTED,"Android rejected the launch on the virtual display");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    void key(int displayId,int code) throws ControlException {
        try {if(!require().injectKey(displayId,code))
            throw new ControlException(ControlException.Code.ACTION_REJECTED,"Android rejected the virtual display key event");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    @Override public synchronized void close() {
        closed=true;remote=null;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        try {if(available()) Shizuku.unbindUserService(args,connection,true);}catch(Exception ignored){}
    }
}
