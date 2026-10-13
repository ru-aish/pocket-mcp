package dev.pocket.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Real separate-package Android UI target for CI. The CI host only launches it:
 * all input and observation are sent through the device's MCP server.
 */
public class FixtureActivity extends Activity {
    private int clicks;
    static FixtureActivity active;
    private TextView activeStatus;
    private android.media.session.MediaSession media;
    static void reply(String text) {if(active!=null) active.activeStatus.setText("STATUS_REPLY:"+text);}
    private TextView label(String text, int id) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setId(id);
        result.setTextSize(20f);
        result.setMinHeight((int) (54 * getResources().getDisplayMetrics().density));
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(16, 24, 16, 16);
        outer.setOnApplyWindowInsetsListener((view,insets)-> {android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());view.setPadding(16+bars.left,24+bars.top,16+bars.right,16+bars.bottom);return insets;});
        outer.setFocusableInTouchMode(true);outer.requestFocus();

        outer.addView(label("MCP_TEST_FIXTURE", View.NO_ID));
        EditText input = new EditText(this);
        input.setId(R.id.mcp_test_input);
        input.setHint("MCP_INPUT");
        input.setSingleLine(true);
        outer.addView(input);

        EditText inputFocused = new EditText(this);
        inputFocused.setId(R.id.mcp_focused_input);
        inputFocused.setHint("MCP_FOCUSED_INPUT");
        inputFocused.setSingleLine(true);
        outer.addView(inputFocused);

        TextView status = label("STATUS_READY", R.id.mcp_test_status);
        activeStatus=status;active=this;
        inputFocused.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        inputFocused.setOnEditorActionListener((v,action,event)-> {
            status.setText("STATUS_ENTERED:"+inputFocused.getText());
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(),0);
            outer.requestFocus();
            return true;
        });

        Button click = new Button(this);
        click.setId(R.id.mcp_test_click);
        click.setText("MCP_CLICK");
        click.setOnClickListener(v -> status.setText("STATUS_CLICKED:" + input.getText().toString()+" COUNT:"+(++clicks)));
        outer.addView(click);

        Button longClick = new Button(this);
        longClick.setId(R.id.mcp_test_long_click);
        longClick.setText("MCP_LONG_CLICK");
        longClick.setOnLongClickListener(v -> {
            status.setText("STATUS_LONG_CLICKED");
            return true;
        });
        outer.addView(longClick);
        outer.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.mcp_test_scroll);
        scroll.setContentDescription("MCP_SCROLL_CONTAINER");
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i <= 25; i++) scrollContent.addView(label("MCP_ROW_" + i, View.NO_ID));
        scrollContent.addView(label("MCP_BOTTOM_MARKER", View.NO_ID));
        scroll.addView(scrollContent);
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(outer);
        seedProviders();
        prepareAppActions(status);
    }
    private void prepareAppActions(TextView status) {
        media=new android.media.session.MediaSession(this,"PocketFixture");
        long actions=android.media.session.PlaybackState.ACTION_PLAY|android.media.session.PlaybackState.ACTION_PAUSE;
        media.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(android.media.session.PlaybackState.STATE_PAUSED,0,0).build());
        media.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() {status.setText("STATUS_MEDIA_PLAYING");media.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(3,0,1).build());}
            @Override public void onPause() {status.setText("STATUS_MEDIA_PAUSED");media.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(2,0,0).build());}
        });media.setActive(true);
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
        manager.createNotificationChannel(new android.app.NotificationChannel("fixture","MCP test fixture",android.app.NotificationManager.IMPORTANCE_LOW));
        android.app.PendingIntent pending=android.app.PendingIntent.getBroadcast(this,0,new android.content.Intent(this,FixtureReplyReceiver.class),android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_MUTABLE);
        android.app.Person sender=new android.app.Person.Builder().setName("Pocket MCP Test Ada").build();
        android.app.Notification.MessagingStyle style=new android.app.Notification.MessagingStyle(new android.app.Person.Builder().setName("You").build()).setConversationTitle("Pocket MCP Test Ada").setGroupConversation(false).addMessage("Synthetic reply test",System.currentTimeMillis(),sender);
        android.app.Notification notification=new android.app.Notification.Builder(this,"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style)
            .addAction(new android.app.Notification.Action.Builder(null,"Reply",pending).addRemoteInput(new android.app.RemoteInput.Builder("reply").setLabel("Reply").build()).build()).build();
        manager.notify(42,notification);
    }
    @Override protected void onDestroy() {if(active==this) active=null;if(media!=null) media.release();super.onDestroy();}
    private void seedProviders() {
        try {
            if(checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                String name="Pocket MCP Test Ada";
                try(android.database.Cursor c=getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{"_id"},"display_name=?",new String[]{name},null)) {
                    if(c==null || !c.moveToFirst()) {
                        java.util.ArrayList<android.content.ContentProviderOperation> ops=new java.util.ArrayList<>();
                        ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI).withValue("account_type",null).withValue("account_name",null).build());
                        ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI).withValueBackReference("raw_contact_id",0).withValue("mimetype",android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue("data1",name).build());
                        ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI).withValueBackReference("raw_contact_id",0).withValue("mimetype",android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue("data1","+15550100420").withValue("data2",2).build());
                        getContentResolver().applyBatch(android.provider.ContactsContract.AUTHORITY,ops);
                    }
                }
            }
            if(checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.content.ContentValues v=new android.content.ContentValues();
                v.put("account_name","pocket-test");v.put("account_type",android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL);v.put("name","Pocket Test Calendar");v.put("calendar_displayName","Pocket Test Calendar");v.put("calendar_color",0xff164c69);v.put("calendar_access_level",700);v.put("ownerAccount","pocket-test");v.put("calendar_timezone","UTC");v.put("visible",1);v.put("sync_events",1);
                try(android.database.Cursor c=getContentResolver().query(android.provider.CalendarContract.Calendars.CONTENT_URI,new String[]{"_id"},"name=?",new String[]{"Pocket Test Calendar"},null)) {
                    if(c==null || !c.moveToFirst()) getContentResolver().insert(android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter("caller_is_syncadapter","true").appendQueryParameter("account_name","pocket-test").appendQueryParameter("account_type",android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL).build(),v);
                }
            }
        } catch(Exception e) {android.util.Log.e("PocketFixture","Provider seed failed: "+e.getClass().getSimpleName());}
    }
}
