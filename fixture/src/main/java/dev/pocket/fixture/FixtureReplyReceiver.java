package dev.pocket.fixture;

public final class FixtureReplyReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context,android.content.Intent intent) {
        android.os.Bundle results=android.app.RemoteInput.getResultsFromIntent(intent);
        if(results!=null) FixtureActivity.reply(String.valueOf(results.getCharSequence("reply","")));
    }
}
