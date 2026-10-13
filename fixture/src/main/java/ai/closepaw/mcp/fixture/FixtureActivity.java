package ai.closepaw.mcp.fixture;

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
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(16, 24, 16, 16);

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

        Button click = new Button(this);
        click.setId(R.id.mcp_test_click);
        click.setText("MCP_CLICK");
        click.setOnClickListener(v -> status.setText("STATUS_CLICKED:" + input.getText().toString()));
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
    }
}
