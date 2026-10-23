package com.omerbitas.rtspwatch;

import android.app.Activity;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.graphics.Bitmap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen live view. Sound starts off: tap to turn it on/off, swipe up/down for volume,
 * double-tap to switch between fit and fill, long-press for statistics, swipe right to go back.
 */
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
    private boolean showStats;
    private int lastRendered;

    // Away from home (no Wi-Fi, or the camera can't be reached directly): Home Assistant pictures.
    private HomeAssistant homeAssistant;
    private HomeAssistant.Stream awayStream;
    private ImageView awayView;
    private boolean onWifi;
    private int directFailures;

    private RtspClient client;
    private Thread clientThread;
    private VideoDecoder decoder;
    private volatile AudioPlayer audio;
    private AudioManager audioManager;
    private boolean muted = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        url = getIntent().getStringExtra(EXTRA_URL);
        surfaceView = findViewById(R.id.surface);
        status = findViewById(R.id.status);
        connectivity = getSystemService(ConnectivityManager.class);
        surfaceView.getHolder().addCallback(this);
        awayView = findViewById(R.id.away_image);
        homeAssistant = HomeAssistant.fromResources(this);

        audioManager = getSystemService(AudioManager.class);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleMute();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fill = !fill;
                layoutVideo();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                toggleStats();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (Math.abs(velocityY) <= Math.abs(velocityX)) return false; // leave swipe-to-dismiss alone
                changeVolume(velocityY < 0);
                return true;
            }
        });
        findViewById(R.id.root).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestures.onTouchEvent(event);
            }
        });
    }

    /** Watches with a rotating bezel or crown can use it for volume too. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta != 0) changeVolume(delta > 0);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private void changeVolume(boolean up) {
        if (muted && up) toggleMute();
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    private void toggleMute() {
        if (awayStream != null) {
            Toast.makeText(this, R.string.sound_home_only, Toast.LENGTH_SHORT).show();
            return;
        }
        muted = !muted;
        AudioPlayer a = audio;
        if (a != null) a.setMuted(muted);
        Toast.makeText(this, muted ? R.string.muted : R.string.unmuted, Toast.LENGTH_SHORT).show();
    }

    private void toggleStats() {
        showStats = !showStats;
        if (showStats) {
            handler.removeCallbacks(diagnosticsTicker);
            handler.post(diagnosticsTicker);
        } else if (pictureShown) {
            showStatus(null);
        }
    }

    private static volatile boolean active;

    /** True while the live view is on screen; the tile then leaves the camera alone. */
    static boolean isActive() {
        return active;
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        CameraTileService.stopFeed();
        started = true;
        onWifi = false;
        directFailures = 0;
        requestWifi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        active = false;
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
                        onWifi = true;
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
        if (!started || !networkReady || surface == null || client != null || awayStream != null) return;
        // The camera's LAN address only works on Wi-Fi; otherwise (or if it failed) use Home Assistant.
        if (homeAssistant != null && (!onWifi || directFailures > 0)) {
            startAwaySession();
            return;
        }
        awayView.setVisibility(View.GONE);
        stage = getString(R.string.buffering);
        codecLabel = "";
        frameCount = 0;
        keyFrameCount = 0;
        pictureShown = false;
        lastRendered = 0;

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
                        if (!showStats) showStatus(null);
                        if (muted && audio != null) {
                            Toast.makeText(PlayerActivity.this, R.string.tap_for_sound, Toast.LENGTH_SHORT).show();
                        }
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
            public void onAudioTrack(RtspClient.AudioInfo info) {
                try {
                    audio = new AudioPlayer(info, muted);
                } catch (Exception e) {
                    audio = null; // no sound, but keep the video going
                }
            }

            @Override
            public void onAudioFrame(byte[] data, int offset, int length) {
                AudioPlayer a = audio;
                if (a != null) a.feed(data, offset, length);
            }

            @Override
            public void onError(final Exception e) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != self[0]) return; // stale session
                        directFailures++;
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

    private void startAwaySession() {
        stage = getString(R.string.away_mode);
        pictureShown = false;
        awayView.setVisibility(View.VISIBLE);
        int size = Math.max(findViewById(R.id.root).getWidth(), 480);
        final HomeAssistant.Stream[] self = new HomeAssistant.Stream[1];
        awayStream = homeAssistant.new Stream(size, new HomeAssistant.FrameListener() {
            @Override
            public void onFrame(final Bitmap frame) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (awayStream != self[0]) return;
                        awayView.setImageBitmap(frame);
                        if (!pictureShown) {
                            pictureShown = true;
                            if (!showStats) showStatus(null);
                        }
                    }
                });
            }

            @Override
            public void onError(final Exception e) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (awayStream != self[0]) return;
                        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        showStatus(message + "\n" + getString(R.string.retrying));
                        stopSession();
                        handler.postDelayed(retry, RETRY_DELAY_MS);
                    }
                });
            }
        });
        self[0] = awayStream;
        new Thread(awayStream, "home-assistant").start();
        handler.removeCallbacks(diagnosticsTicker);
        handler.post(diagnosticsTicker);
    }

    private final Runnable diagnosticsTicker = new Runnable() {
        @Override
        public void run() {
            if ((client == null && awayStream == null) || (pictureShown && !showStats)) return;
            updateDiagnostics();
            handler.postDelayed(this, 1000);
        }
    };

    /** Shows what stage the connection is at, so a stuck stream can be diagnosed on the watch. */
    private void updateDiagnostics() {
        if ((pictureShown && !showStats) || (client == null && awayStream == null)) return;
        StringBuilder sb = new StringBuilder(pictureShown ? "" : stage);
        if (awayStream != null) {
            if (pictureShown) sb.append(getString(R.string.away_mode));
            sb.append("\nPictures ").append(awayStream.frames());
            showStatus(sb.toString());
            return;
        }
        if (!codecLabel.isEmpty()) sb.append("\n\n").append(codecLabel).append(" video");
        if (client.rtpPackets() > 0) sb.append("\nPackets ").append(client.rtpPackets());
        if (frameCount > 0) {
            sb.append("\nReceived ").append(frameCount).append(" frames (")
                    .append(keyFrameCount).append(" key)");
        }
        VideoDecoder dec = decoder;
        if (dec != null && dec.codecName() != null) {
            sb.append("\nDecoder ").append(dec.codecName())
                    .append("\nIn ").append(dec.queuedFrames())
                    .append(" / out ").append(dec.renderedFrames())
                    .append(" / dropped ").append(dec.droppedFrames());
            if (!dec.sizeInfo().isEmpty()) sb.append("\n").append(dec.sizeInfo());
            int rendered = dec.renderedFrames();
            sb.append("\n").append(Math.max(0, rendered - lastRendered)).append(" fps");
            lastRendered = rendered;
        }
        AudioPlayer a = audio;
        if (a != null) sb.append("\nAudio ").append(a.describe());
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
        if (audio != null) {
            audio.release();
            audio = null;
        }
        if (awayStream != null) {
            awayStream.stop();
            awayStream = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // UI

    /** Sizes the surface to keep the video's aspect ratio, either fitting or filling the screen. */
    private void layoutVideo() {
        View root = findViewById(R.id.root);
        int w = root.getWidth();
        int h = root.getHeight();
        awayView.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
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
