package dev.pocket.mcp;

import android.net.DnsResolver;
import android.os.CancellationSignal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Check the app's Android resolver without changing VPN, DNS settings or TLS server identity. */
final class TunnelDns {
    static void check() throws InterruptedException,UnknownHostException {
        CountDownLatch done=new CountDownLatch(1);
        AtomicBoolean resolved=new AtomicBoolean();
        CancellationSignal cancel=new CancellationSignal();
        try {
            DnsResolver.getInstance().query(null,"connect.ngrok-agent.com",DnsResolver.FLAG_EMPTY,Runnable::run,cancel,new DnsResolver.Callback<List<InetAddress>>() {
                @Override public void onAnswer(List<InetAddress> answer,int rcode) {resolved.set(rcode==0 && !answer.isEmpty());done.countDown();}
                @Override public void onError(DnsResolver.DnsException error) {done.countDown();}
            });
            if(!done.await(8,TimeUnit.SECONDS) || !resolved.get()) throw new UnknownHostException("ngrok DNS preflight failed");
        } finally {cancel.cancel();}
    }
}
