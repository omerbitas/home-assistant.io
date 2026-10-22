package com.omerbitas.rtspwatch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Lists saved streams. Tap to watch, long-press to delete.
 *
 * <p>When exactly one stream is saved, launching the app opens it straight away.
 *
 * <p>Streams can also be added from a computer instead of the watch keyboard:
 * {@code adb shell am start -n com.omerbitas.rtspwatch/.MainActivity --es add_url "rtsp://..."}
 */
public class MainActivity extends Activity {

    static final String EXTRA_ADD_URL = "add_url";

    private StreamStore store;
    private LinearLayout list;
    private TextView empty;
    private TextView hint;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new StreamStore(this);
        list = findViewById(R.id.stream_list);
        empty = findViewById(R.id.empty);
        hint = findViewById(R.id.hint);
        input = findViewById(R.id.url_input);

        findViewById(R.id.add_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addStream(input.getText().toString());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                addStream(input.getText().toString());
                return true;
            }
        });
        // Lets the rotating bezel / crown scroll the list.
        findViewById(R.id.scroll).requestFocus();

        boolean adding = getIntent() != null && getIntent().hasExtra(EXTRA_ADD_URL);
        handleIntent(getIntent());
        render();

        // With a single saved stream, opening the app goes straight to the live view.
        List<StreamStore.Stream> streams = store.load();
        if (savedInstanceState == null && !adding && streams.size() == 1) {
            startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_URL, streams.get(0).url));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        render();
    }

    private void handleIntent(Intent intent) {
        String url = intent != null ? intent.getStringExtra(EXTRA_ADD_URL) : null;
        if (url != null) {
            intent.removeExtra(EXTRA_ADD_URL);
            addStream(url);
        }
    }

    private void addStream(String raw) {
        String url = raw.trim();
        String lower = url.toLowerCase();
        if (!lower.startsWith("rtsp://") && !lower.startsWith("rtsps://")) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        store.add(url);
        input.getText().clear();
        render();
    }

    private void render() {
        List<StreamStore.Stream> streams = store.load();
        list.removeAllViews();
        empty.setVisibility(streams.isEmpty() ? View.VISIBLE : View.GONE);
        hint.setVisibility(streams.isEmpty() ? View.GONE : View.VISIBLE);

        int pad = dp(12);
        for (int i = 0; i < streams.size(); i++) {
            final int index = i;
            final StreamStore.Stream stream = streams.get(i);
            TextView row = new TextView(this);
            row.setText(stream.label());
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            row.setMaxLines(2);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.stream_item_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, PlayerActivity.class)
                            .putExtra(PlayerActivity.EXTRA_URL, stream.url));
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    store.remove(index);
                    Toast.makeText(MainActivity.this, R.string.deleted, Toast.LENGTH_SHORT).show();
                    render();
                    return true;
                }
            });
            list.addView(row);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
