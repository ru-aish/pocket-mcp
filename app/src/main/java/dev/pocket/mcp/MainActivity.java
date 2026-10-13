package dev.pocket.mcp;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/** Small native settings screen. No embedded model, browser or UI framework. */
public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state;
    private EditText ngrok,bearer,domain;
    private Secrets secrets;
    private boolean refreshing;
    private final Runnable refresh=new Runnable(){public void run(){if(!refreshing)return;render();handler.postDelayed(this,1000);}};
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(LinearLayout parent,String text,int size) {TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(Color.rgb(28,48,64));t.setPadding(0,dp(8),0,dp(8));parent.addView(t);return t;}
    private Button button(LinearLayout parent,String text,String description,Runnable work) {Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setContentDescription(description);b.setMinHeight(dp(48));parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->work.run());return b;}
    private EditText field(LinearLayout parent,String hint,String description,boolean secret) {EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setContentDescription(description);e.setTextSize(15);e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_URI));e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);parent.addView(e,new LinearLayout.LayoutParams(-1,dp(54)));return e;}
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);secrets=new Secrets(this);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(12),dp(20),dp(24));body.setBackgroundColor(Color.rgb(244,248,251));scroll.addView(body);
        body.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20)+i.left,dp(12)+i.top,dp(20)+i.right,dp(24)+i.bottom);return insets;});
        TextView title=label(body,"Pocket MCP",28);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        label(body,"Your phone, connected to your agent.",16);
        state=label(body,"Connection stopped",14);state.setTypeface(Typeface.MONOSPACE);state.setContentDescription("P_STATUS");state.setTextIsSelectable(true);state.setPadding(dp(12),dp(12),dp(12),dp(12));state.setBackgroundColor(Color.rgb(221,236,246));
        button(body,"Start public connection","P_START_PUBLIC",()->start(false));
        button(body,"Stop connection","P_STOP",()->{secrets.enabled(false);stopService(new Intent(this,ServerService.class));render();});
        label(body,"Connection settings",20);
        ngrok=field(body,"ngrok authtoken","P_NGROK",true);
        domain=field(body,"Reserved domain (optional)","P_DOMAIN",false);
        bearer=field(body,"New bearer token (optional)","P_BEARER",true);
        label(body,"Saved credentials stay encrypted on this phone. Leave a field empty to keep its saved value.",13);
        button(body,"Save connection settings","P_SAVE",this::save);
        button(body,"Copy MCP connection details","P_COPY",()->{try {String url=ServerService.status().optString("public_url");String config=Json.obj("url",url.isEmpty()?"http://127.0.0.1:8765/mcp":url,"bearer_token",secrets.bearer()).toString(2);ClipData clip=ClipData.newPlainText("Pocket MCP connection",config);android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);getSystemService(ClipboardManager.class).setPrimaryClip(clip);toast("Connection details copied");}catch(Exception e){toast("Could not read saved credentials");}});
        label(body,"Phone access",20);
        label(body,"Enable only the permissions your agent needs. Screen control requires accessibility; notification replies and media controls require notification access.",14);
        button(body,"Enable screen control","P_ACCESSIBILITY",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(body,"Enable notification access","P_NOTIFICATIONS",()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(body,"Allow contacts and calls","P_CALL_PERMISSIONS",()->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS,Manifest.permission.CALL_PHONE,Manifest.permission.READ_PHONE_STATE},1));
        button(body,"Allow calendar access","P_CALENDAR_PERMISSIONS",()->requestPermissions(new String[]{Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR},2));
        button(body,"Allow SMS sending","P_SMS_PERMISSIONS",()->requestPermissions(new String[]{Manifest.permission.SEND_SMS},3));
        button(body,"Battery settings","P_BATTERY",()->startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        label(body,"Local access",20);label(body,"A local connection stays on this phone. For remote access, use the public connection above.",13);
        button(body,"Start local connection","P_START_LOCAL",()->start(true));
        label(body,"Android 12+ · v"+BuildConfig.VERSION_NAME,12);body.setFocusableInTouchMode(true);body.requestFocus();setContentView(scroll);
    }
    private void toast(String text) {Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void save() {
        if(ServerService.status().optBoolean("running") || !java.util.List.of("stopped","failed").contains(ServerService.status().optString("stage"))) {toast("Stop the connection before changing credentials");return;}
        try {
            String n=ngrok.getText().toString().trim(), b=bearer.getText().toString(),d=domain.getText().toString().trim();
            if(!b.isEmpty() && !b.matches("[A-Za-z0-9_-]{32,128}")) {toast("Use a bearer token with 32–128 letters, digits, underscores or dashes");return;}
            if(!d.isEmpty() && !d.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{2,200}")) {toast("Enter a domain name without https:// or a path");return;}
            if(!n.isEmpty()) secrets.write("ngrok",n);if(!b.isEmpty()) secrets.write("bearer",b);if(!d.isEmpty()) secrets.write("domain",d);
            secrets.bearer();ngrok.setText("");bearer.setText("");domain.setText("");toast("Connection settings saved");
        } catch(Exception e) {toast("Settings could not be encrypted. Stop and try again.");}
    }
    private void start(boolean local) {
        if(ServerService.status().optBoolean("running")) {toast("Stop the current connection first");return;}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        try {secrets.bearer();if(!local && secrets.ngrok().isEmpty()) {toast("Save your ngrok authtoken first");return;}startForegroundService(new Intent(this,ServerService.class).setAction(local?"LOCAL":"PUBLIC"));}
        catch(Exception e) {toast("Connection could not start. Check saved settings.");}
    }
    private void render() {
        org.json.JSONObject status=ServerService.status();String url=status.optString("public_url");String message=status.optString("diagnostic");
        String updated="Connection: "+status.optString("stage")+"\nScreen control: "+(PhoneObserver.connected==null?"not enabled":"ready")+"\nNotification access: "+(PhoneNotifications.connected==null?"not enabled":"ready")+(url.isEmpty()?"":"\n"+url)+(message.isEmpty()?"":"\n"+message);
        if(!updated.contentEquals(state.getText())) state.setText(updated);
    }
    @Override protected void onResume() {super.onResume();refreshing=true;handler.post(refresh);}
    @Override protected void onPause() {refreshing=false;handler.removeCallbacks(refresh);super.onPause();}
}
