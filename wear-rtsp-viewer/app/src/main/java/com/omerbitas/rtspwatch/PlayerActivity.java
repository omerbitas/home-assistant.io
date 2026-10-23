package com.omerbitas.rtspwatch;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Full-screen live view. Tap to switch between fit and fill; swipe right to go back. */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    static final String EXTRA_URL = "url";

    private static final long WIFI_TIMEOUT_MS = 10000;
    private static final long RETRY_DELAY_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SurfaceView surfaceView;
    private TextView status;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    private String url;
    private boolean started;
    private boolean networkReady;
    private SurfaceHolder surface;
    private boolean fill;
    private int videoWidth;
    private int videoHeight;

    // Diagnostics shown until the first picture appears.
    private String stage;
    private String codecLabel = "";
    private volatile int frameCount;
    private volatile int keyFrameCount;
    private boolean pictureShown;

    private RtspClient client;
    private Thread clientThread;
    private VideoDecoder decoder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        url = getIntent().getStringExtra(EXTRA_URL);
        surfaceView = findViewById(R.id.surface);
        status = findViewById(R.id.status);
        connectivity = getSystemService(ConnectivityManager.class);
        surfaceView.getHolder().addCallback(this);

        findViewById(R.id.root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fill = !fill;
                layoutVideo();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        requestWifi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        networkReady = false;
        handler.removeCallbacksAndMessages(null);
        stopSession();
        releaseNetwork();
    }

    // ---------------------------------------------------------------------------------------
    // Network

    /**
     * Wear OS normally routes traffic through the paired phone over Bluetooth, which is too slow
     * for video and usually can't reach cameras on the LAN. Ask for a direct Wi-Fi connection and
     * fall back to whatever network is available (e.g. LTE) if Wi-Fi doesn't come up in time.
     */
    private void requestWifi() {
        showStatus(getString(R.string.connecting_wifi));
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                connectivity.bindProcessToNetwork(network);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        handler.removeCallbacks(networkFallback);
                        onNetworkReady();
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                connectivity.bindProcessToNetwork(null);
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivity.requestNetwork(request, networkCallback);
        handler.postDelayed(networkFallback, WIFI_TIMEOUT_MS);
    }

    private final Runnable networkFallback = new Runnable() {
        @Override
        public void run() {
            onNetworkReady();
        }
    };

    private void onNetworkReady() {
        if (!started || networkReady) return;
        networkReady = true;
        startSession();
    }

    private void releaseNetwork() {
        if (networkCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
            networkCallback = null;
        }
        connectivity.bindProcessToNetwork(null);
    }

    // ---------------------------------------------------------------------------------------
    // Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder;
        startSession();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        stopSession();
    }

    // ---------------------------------------------------------------------------------------
    // Streaming

    private void startSession() {
        if (!started || !networkReady || surface == null || client != null) return;
        stage = getString(R.string.buffering);
        codecLabel = "";
        frameCount = 0;
        keyFrameCount = 0;
        pictureShown = false;

        final VideoDecoder[] decRef = new VideoDecoder[1];
        final VideoDecoder dec = new VideoDecoder(surface.getSurface(), new VideoDecoder.Listener() {
            @Override
            public void onVideoSize(final int width, final int height) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        videoWidth = width;
                        videoHeight = height;
                        layoutVideo();
                    }
                });
            }

            @Override
            public void onFirstFrameRendered() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (decoder != decRef[0]) return;
                        pictureShown = true;
                        showStatus(null);
                    }
                });
            }
        });
        decRef[0] = dec;
        decoder = dec;
        final RtspClient[] self = new RtspClient[1];
        client = new RtspClient(url, new RtspClient.Listener() {
            @Override
            public void onStatus(final String text) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (text != null) stage = text;
                        updateDiagnostics();
                    }
                });
            }

            @Override
            public void onTrack(String mime, byte[][] parameterSets) {
                codecLabel = RtspClient.MIME_H265.equals(mime) ? "H.265" : "H.264";
                dec.setTrack(mime, parameterSets);
            }

            @Override
            public void onFrame(byte[] data, int length, long timestampUs, boolean keyFrame) {
                frameCount++;
                if (keyFrame) keyFrameCount++;
                try {
                    dec.feed(data, length, timestampUs, keyFrame);
                } catch (Exception e) {
                    self[0].stop();
                    onError(e);
                }
            }

            @Override
            public void onError(final Exception e) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != self[0]) return; // stale session
                        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        showStatus(message + "\n" + getString(R.string.retrying));
                        stopSession();
                        handler.postDelayed(retry, RETRY_DELAY_MS);
                    }
                });
            }
        });
        self[0] = client;
        clientThread = new Thread(client, "rtsp");
        clientThread.start();
        handler.removeCallbacks(diagnosticsTicker);
        handler.post(diagnosticsTicker);
    }

    private final Runnable diagnosticsTicker = new Runnable() {
        @Override
        public void run() {
            if (client == null || pictureShown) return;
            updateDiagnostics();
            handler.postDelayed(this, 1000);
        }
    };

    /** Shows what stage the connection is at, so a stuck stream can be diagnosed on the watch. */
    private void updateDiagnostics() {
        if (pictureShown || client == null) return;
        StringBuilder sb = new StringBuilder(stage);
        if (!codecLabel.isEmpty()) sb.append("\n\n").append(codecLabel).append(" video");
        if (frameCount > 0) {
            sb.append("\nReceived ").append(frameCount).append(" frames (")
                    .append(keyFrameCount).append(" key)");
        }
        VideoDecoder dec = decoder;
        if (dec != null && dec.codecName() != null) {
            sb.append("\nDecoder ").append(dec.codecName())
                    .append("\nIn ").append(dec.queuedFrames())
                    .append(" / out ").append(dec.renderedFrames());
        }
        showStatus(sb.toString());
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            startSession();
        }
    };

    private void stopSession() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(diagnosticsTicker);
        if (client != null) {
            client.stop();
            client = null;
        }
        if (clientThread != null) {
            try {
                clientThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            clientThread = null;
        }
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // UI

    /** Sizes the surface to keep the video's aspect ratio, either fitting or filling the screen. */
    private void layoutVideo() {
        View root = findViewById(R.id.root);
        int w = root.getWidth();
        int h = root.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || w == 0 || h == 0) return;
        float scale = fill
                ? Math.max((float) w / videoWidth, (float) h / videoHeight)
                : Math.min((float) w / videoWidth, (float) h / videoHeight);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        lp.width = Math.round(videoWidth * scale);
        lp.height = Math.round(videoHeight * scale);
        surfaceView.setLayoutParams(lp);
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }
}
