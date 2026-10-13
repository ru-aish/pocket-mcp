package dev.pocket.mcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SmsReceiptReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        try(OperationStore store=new OperationStore(context)) {store.receipt(intent.getStringExtra("operation"),intent.getStringExtra("stage"),intent.getIntExtra("part",-1),getResultCode());}
    }
}
