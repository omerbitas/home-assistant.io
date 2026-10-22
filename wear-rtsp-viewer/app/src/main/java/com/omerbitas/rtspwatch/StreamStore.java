package com.omerbitas.rtspwatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists the list of saved RTSP streams. */
final class StreamStore {

    static final class Stream {
        final String url;

        Stream(String url) {
            this.url = url;
        }

        /** Display label with any credentials stripped from the URL. */
        String label() {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return url;
            String port = uri.getPort() != -1 ? ":" + uri.getPort() : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            return host + port + path;
        }
    }

    private static final String KEY = "streams";
    private final SharedPreferences prefs;

    StreamStore(Context context) {
        prefs = context.getSharedPreferences("streams", Context.MODE_PRIVATE);
    }

    List<Stream> load() {
        List<Stream> streams = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                streams.add(new Stream(array.getJSONObject(i).getString("url")));
            }
        } catch (JSONException ignored) {
            // Corrupt prefs: start over with an empty list.
        }
        return streams;
    }

    void save(List<Stream> streams) {
        JSONArray array = new JSONArray();
        try {
            for (Stream s : streams) array.put(new JSONObject().put("url", s.url));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    void add(String url) {
        List<Stream> streams = load();
        streams.add(new Stream(url));
        save(streams);
    }

    void remove(int index) {
        List<Stream> streams = load();
        if (index >= 0 && index < streams.size()) streams.remove(index);
        save(streams);
    }
}
