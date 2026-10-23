package com.omerbitas.rtspwatch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.List;

/**
 * Invisible entry point for the tile: opens the first saved stream in a fresh task (so a stale
 * live view in the background is replaced), or the stream list if nothing is saved yet.
 */
public class LiveActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<StreamStore.Stream> streams = new StreamStore(this).load();
        Intent intent = streams.isEmpty()
                ? new Intent(this, MainActivity.class)
                : new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_URL, streams.get(0).url);
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
