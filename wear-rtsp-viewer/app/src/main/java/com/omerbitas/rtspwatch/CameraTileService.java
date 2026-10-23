package com.omerbitas.rtspwatch;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.format.DateFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/**
 * Wear OS tile showing a recent still picture from the camera; tapping it opens the live view.
 *
 * <p>Tiles can't play video, so while the tile is on screen this keeps one camera connection open,
 * takes a frame every couple of seconds and asks the system to redraw (the system decides how often
 * that is allowed). Nothing connects while the live view is open, so it gets the camera to itself. When the tile isn't
 * visible it refreshes at most every 30 seconds, and only when the system asks for the tile.
 *
 * <p>The AndroidX Tiles library isn't used (to keep the app dependency-free), so this speaks the
 * tile binder protocol directly. It mirrors the AIDL interfaces in androidx.wear.tiles
 * (TileProvider, TileCallback, ResourcesCallback, TileUpdateRequesterService) and hand-encodes
 * the protobuf layout (androidx.wear.tiles.proto.Tile, schema 1.0) the tile renderer expects.
 */
public class CameraTileService extends Service {

    private static final String PROVIDER = "androidx.wear.tiles.TileProvider";
    private static final String TILE_CALLBACK = "androidx.wear.tiles.TileCallback";
    private static final String RESOURCES_CALLBACK = "androidx.wear.tiles.ResourcesCallback";
    private static final String UPDATE_REQUESTER = "androidx.wear.tiles.TileUpdateRequesterService";
    private static final String ACTION_BIND = "androidx.wear.tiles.action.BIND_TILE_PROVIDER";
    private static final String ACTION_BIND_UPDATE_REQUESTER = "androidx.wear.tiles.action.BIND_UPDATE_REQUESTER";
    private static final String DEFAULT_SYSUI = "com.google.android.wearable.app";

    private static final int API_VERSION = 4;
    private static final int GET_API_VERSION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int ON_TILE_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int ON_RESOURCES_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int ON_TILE_ENTER = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int ON_TILE_LEAVE = IBinder.FIRST_CALL_TRANSACTION + 8;
    private static final int CALLBACK_UPDATE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int REQUEST_UPDATE = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static final int TILE_DATA_VERSION = 1;
    private static final int RESOURCES_DATA_VERSION = 1;
    private static final String IMAGE_ID = "snapshot";

    private static final int SNAPSHOT_WIDTH = 320;
    private static final long SNAPSHOT_TIMEOUT_MS = 10000;
    private static final long WIFI_TIMEOUT_MS = 4000;
    /** Time between pictures while the tile is on screen. */
    private static final long VISIBLE_INTERVAL_MS = 2000;
    /** Pause before reconnecting after a failure while the tile is on screen. */
    private static final long RETRY_DELAY_MS = 5000;
    /** Stop refreshing if the system never reports that the tile was left. */
    private static final long VISIBLE_MAX_MS = 3 * 60 * 1000;
    /**
     * Refresh interval when the tile is requested but not known to be on screen. Also used as the
     * tile's freshness interval, so the system asks again when it shows a stale tile.
     */
    private static final long BACKGROUND_INTERVAL_MS = 30 * 1000;

    // Shared state: the system binds and unbinds the service often, so keep it static.
    private static final Object LOCK = new Object();
    private static final Map<String, Snapshot> SNAPSHOTS = new LinkedHashMap<>();
    private static Snapshot latest;
    private static boolean loaded;
    private static boolean grabbing;
    private static boolean visible;
    private static long visibleSince;
    private static long lastGrabStart;
    private static boolean lastGrabFailed;
    private static Handler worker;
    private static Snapshot.Feed feed;
    private static WifiRequest feedNetwork;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(PROVIDER);
                return true;
            }
            if (code == GET_API_VERSION) {
                data.enforceInterface(PROVIDER);
                reply.writeNoException();
                reply.writeInt(API_VERSION);
                return true;
            }
            if (code == ON_TILE_REQUEST) {
                data.enforceInterface(PROVIDER);
                data.readInt(); // tile id
                readParcelable(data);
                IBinder callback = data.readStrongBinder();
                if (callback != null) send(callback, TILE_CALLBACK, TILE_DATA_VERSION, buildTile());
                maybeRefresh(getApplicationContext());
                return true;
            }
            if (code == ON_RESOURCES_REQUEST) {
                data.enforceInterface(PROVIDER);
                data.readInt(); // tile id
                byte[] request = readParcelable(data);
                IBinder callback = data.readStrongBinder();
                if (callback != null) {
                    String version = request != null ? Proto.readString(request, 1) : null;
                    send(callback, RESOURCES_CALLBACK, RESOURCES_DATA_VERSION, buildResources(version));
                }
                return true;
            }
            if (code == ON_TILE_ENTER || code == ON_TILE_LEAVE) {
                data.enforceInterface(PROVIDER);
                synchronized (LOCK) {
                    visible = code == ON_TILE_ENTER;
                    if (visible) visibleSince = System.currentTimeMillis();
                }
                if (code == ON_TILE_ENTER) maybeRefresh(getApplicationContext());
                else stopFeed();
                return true;
            }
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                return true; // add/remove/interaction events: nothing to do
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return intent != null && ACTION_BIND.equals(intent.getAction()) ? binder : null;
    }

    /** Reads an AIDL "in" ProtoParcelable argument and returns its payload. */
    private static byte[] readParcelable(Parcel data) {
        if (data.readInt() == 0) return null;
        int version = data.readInt();
        byte[] payload = data.createByteArray();
        if (version >= 2) data.readBundle(CameraTileService.class.getClassLoader());
        return payload;
    }

    /** Calls a one-way callback method taking a single ProtoParcelable. */
    private static void send(IBinder callback, String descriptor, int version, byte[] payload)
            throws RemoteException {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInterfaceToken(descriptor);
            parcel.writeInt(1); // non-null parcelable
            parcel.writeInt(version);
            parcel.writeByteArray(payload);
            callback.transact(CALLBACK_UPDATE, parcel, null, IBinder.FLAG_ONEWAY);
        } finally {
            parcel.recycle();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Snapshots

    private static Snapshot latest(Context context) {
        synchronized (LOCK) {
            if (!loaded) {
                loaded = true;
                Snapshot saved = Snapshot.load(snapshotFile(context));
                if (saved != null) remember(saved);
            }
            return latest;
        }
    }

    private static void remember(Snapshot snapshot) {
        latest = snapshot;
        SNAPSHOTS.put(version(snapshot), snapshot);
        while (SNAPSHOTS.size() > 3) SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
    }

    private static String version(Snapshot snapshot) {
        return snapshot == null ? "none" : "s" + snapshot.timeMillis;
    }

    private static File snapshotFile(Context context) {
        return new File(context.getCacheDir(), "tile-snapshot.bin");
    }

    private static void maybeRefresh(final Context context) {
        Snapshot current = latest(context);
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            // The live view gets the camera to itself: many cameras only allow one or two connections.
            if (PlayerActivity.isActive() || grabbing || feed != null) return;
            final boolean onScreen = visible && now - visibleSince < VISIBLE_MAX_MS;
            long delay;
            if (onScreen) {
                delay = Math.max(0, lastGrabStart + RETRY_DELAY_MS - now);
            } else if ((current == null || now - current.timeMillis > BACKGROUND_INTERVAL_MS)
                    && now - lastGrabStart > BACKGROUND_INTERVAL_MS) {
                delay = 0;
            } else {
                return;
            }
            grabbing = true;
            if (worker == null) {
                HandlerThread thread = new HandlerThread("tile-snapshots");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            worker.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (onScreen) startFeed(context);
                    else grabOnce(context);
                }
            }, delay);
        }
    }

    /** Stops the on-screen feed, e.g. when the tile is left or the live view opens. */
    static void stopFeed() {
        synchronized (LOCK) {
            if (feed != null) {
                feed.stop();
                feed = null;
            }
            if (feedNetwork != null) {
                feedNetwork.release();
                feedNetwork = null;
            }
        }
    }

    /** Runs on the worker thread: keeps one connection open and refreshes the tile from it. */
    private static void startFeed(final Context context) {
        synchronized (LOCK) {
            lastGrabStart = System.currentTimeMillis();
        }
        List<StreamStore.Stream> streams = new StreamStore(context).load();
        if (streams.isEmpty()) {
            synchronized (LOCK) {
                grabbing = false;
            }
            return;
        }
        WifiRequest wifi = WifiRequest.await(context);
        synchronized (LOCK) {
            grabbing = false;
            if (!visible || PlayerActivity.isActive()) {
                wifi.release();
                return;
            }
            feedNetwork = wifi;
            feed = new Snapshot.Feed(streams.get(0).url, wifi.socketFactory(), SNAPSHOT_WIDTH, VISIBLE_INTERVAL_MS,
                    new Snapshot.Feed.Callback() {
                        @Override
                        public void onSnapshot(Snapshot snapshot) {
                            snapshot.save(snapshotFile(context));
                            boolean keepGoing;
                            synchronized (LOCK) {
                                remember(snapshot);
                                lastGrabFailed = false;
                                keepGoing = visible && !PlayerActivity.isActive()
                                        && System.currentTimeMillis() - visibleSince < VISIBLE_MAX_MS;
                            }
                            if (!keepGoing) stopFeed();
                            requestUpdate(context);
                        }

                        @Override
                        public void onError(Exception e) {
                            synchronized (LOCK) {
                                lastGrabFailed = true;
                            }
                            stopFeed();
                            requestUpdate(context);
                            maybeRefresh(context); // retries after a pause while still on screen
                        }
                    });
        }
    }

    /** Runs on the worker thread: one connection, one picture, then ask the system to redraw. */
    private static void grabOnce(Context context) {
        synchronized (LOCK) {
            lastGrabStart = System.currentTimeMillis();
        }
        boolean failed = true;
        List<StreamStore.Stream> streams = new StreamStore(context).load();
        if (!streams.isEmpty()) {
            WifiRequest wifi = WifiRequest.await(context);
            try {
                Snapshot snapshot = Snapshot.grab(streams.get(0).url, wifi.socketFactory(), SNAPSHOT_WIDTH,
                        SNAPSHOT_TIMEOUT_MS);
                snapshot.save(snapshotFile(context));
                synchronized (LOCK) {
                    remember(snapshot);
                }
                failed = false;
            } catch (Exception e) {
                // Keep showing the previous picture, marked as offline.
            } finally {
                wifi.release();
            }
        }
        synchronized (LOCK) {
            lastGrabFailed = failed;
            grabbing = false;
        }
        requestUpdate(context);
        maybeRefresh(context); // switches to the live feed if the tile came on screen meanwhile
    }

    /** Like the live view: use Wi-Fi directly rather than the Bluetooth link to the phone. */
    private static final class WifiRequest extends ConnectivityManager.NetworkCallback {
        private final ConnectivityManager connectivity;
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile Network network;

        private WifiRequest(Context context) {
            connectivity = context.getSystemService(ConnectivityManager.class);
        }

        static WifiRequest await(Context context) {
            WifiRequest request = new WifiRequest(context);
            try {
                request.connectivity.requestNetwork(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), request);
                request.ready.await(WIFI_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | RuntimeException ignored) {
            }
            return request;
        }

        @Override
        public void onAvailable(Network network) {
            this.network = network;
            ready.countDown();
        }

        /** Wi-Fi if it came up in time, otherwise the default network. */
        SocketFactory socketFactory() {
            Network n = network;
            return n != null ? n.getSocketFactory() : null;
        }

        void release() {
            try {
                connectivity.unregisterNetworkCallback(this);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Asks the system tile renderer to call onTileRequest again (it may rate-limit this). */
    private static void requestUpdate(final Context context) {
        String sysui = Settings.Global.getString(context.getContentResolver(), "clockwork_sysui_package");
        Intent intent = new Intent(ACTION_BIND_UPDATE_REQUESTER)
                .setPackage(sysui == null || sysui.isEmpty() ? DEFAULT_SYSUI : sysui);
        List<ResolveInfo> services = context.getPackageManager().queryIntentServices(intent, 0);
        if (services.isEmpty()) return;
        intent.setClassName(services.get(0).serviceInfo.packageName, services.get(0).serviceInfo.name);
        final ComponentName tile = new ComponentName(context, CameraTileService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.writeInterfaceToken(UPDATE_REQUESTER);
                    parcel.writeInt(1);
                    tile.writeToParcel(parcel, 0);
                    parcel.writeInt(1); // TileUpdateRequestData: version 1, empty payload
                    parcel.writeInt(1);
                    parcel.writeByteArray(new byte[0]);
                    service.transact(REQUEST_UPDATE, parcel, null, IBinder.FLAG_ONEWAY);
                } catch (RemoteException ignored) {
                } finally {
                    parcel.recycle();
                    context.unbindService(this);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException ignored) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Layout

    private byte[] buildTile() {
        Snapshot snapshot = latest(getApplicationContext());
        boolean failed;
        synchronized (LOCK) {
            failed = lastGrabFailed;
        }
        boolean hasStream = !new StreamStore(this).load().isEmpty();
        String caption;
        if (!hasStream) {
            caption = getString(R.string.tile_no_stream);
        } else if (snapshot == null) {
            caption = getString(failed ? R.string.tile_offline : R.string.tile_loading);
        } else {
            String pattern = DateFormat.is24HourFormat(this) ? "HH:mm:ss" : "h:mm:ss a";
            String time = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(snapshot.timeMillis));
            caption = (failed ? getString(R.string.tile_offline) + " · " : "▶ ") + time;
        }
        return buildTile(getPackageName(), LiveActivity.class.getName(), version(snapshot),
                snapshot != null, caption);
    }

    static byte[] buildTile(String packageName, String activity, String resourcesVersion, boolean hasImage,
            String caption) {
        Proto launch = new Proto().message(1, new Proto() // Action.launch_action
                .message(1, new Proto() // LaunchAction.android_activity
                        .string(1, packageName)
                        .string(2, activity)));
        Proto clickable = new Proto().string(1, "open").message(2, launch);
        Proto expanded = new Proto().message(2, new Proto()); // ExpandedDimensionProp

        Proto root = new Proto();
        if (hasImage) {
            Proto image = new Proto()
                    .message(1, new Proto().string(1, IMAGE_ID))
                    .message(2, expanded)
                    .message(3, expanded)
                    .message(4, new Proto().varint(1, 2)); // CONTENT_SCALE_MODE_CROP
            root.message(1, element(6, image));
        } else {
            Proto play = box(new Proto().message(1, dp(96)),
                    new Proto().message(5, new Proto() // Background: blue circle
                            .message(1, color(0xFF03A9F4))
                            .message(2, new Proto().message(1, dp(48)))),
                    text("▶", 36, 0xFFFFFFFF, true, null));
            root.message(1, element(3, play));
        }

        // Caption near the bottom edge, on a dark pill so it stays readable over the picture.
        Proto pill = new Proto()
                .message(3, new Proto() // Padding
                        .message(1, dp(10)).message(2, dp(10)).message(3, dp(4)).message(4, dp(4)))
                .message(5, new Proto()
                        .message(1, color(0xB0000000))
                        .message(2, new Proto().message(1, dp(12))));
        Proto captionBox = new Proto()
                .message(1, element(5, text(caption, 13, 0xFFFFFFFF, true, pill)))
                .message(2, expanded)
                .message(3, expanded)
                .message(4, new Proto().varint(1, 2)) // HORIZONTAL_ALIGN_CENTER
                .message(5, new Proto().varint(1, 3)) // VERTICAL_ALIGN_BOTTOM
                .message(6, new Proto().message(3, new Proto().message(4, dp(28)))); // bottom padding
        root.message(1, element(3, captionBox))
                .message(2, expanded)
                .message(3, expanded)
                .message(4, new Proto().varint(1, 2))
                .message(5, new Proto().varint(1, 2)) // VERTICAL_ALIGN_CENTER
                .message(6, new Proto()
                        .message(1, clickable)
                        .message(5, new Proto().message(1, color(0xFF000000))));

        Proto layout = new Proto().message(1, element(3, root));
        Proto timeline = new Proto().message(1, new Proto().message(2, layout));
        return new Proto()
                .string(1, resourcesVersion)
                .message(2, timeline)
                .message(3, new Proto().varint(1, 1).varint(2, 0)) // schema 1.0
                .varint(4, BACKGROUND_INTERVAL_MS) // freshness_interval_millis
                .bytes();
    }

    /** Resources for the requested version: the matching snapshot (or the latest) as RGB 565. */
    private byte[] buildResources(String requestedVersion) {
        Snapshot snapshot;
        synchronized (LOCK) {
            snapshot = requestedVersion != null ? SNAPSHOTS.get(requestedVersion) : null;
        }
        if (snapshot == null) snapshot = latest(getApplicationContext());
        String version = requestedVersion != null ? requestedVersion : version(snapshot);
        return buildResources(version, snapshot);
    }

    static byte[] buildResources(String version, Snapshot snapshot) {
        Proto resources = new Proto().string(1, version);
        if (snapshot != null) {
            Proto inline = new Proto()
                    .raw(1, snapshot.rgb565)
                    .varint(2, snapshot.width)
                    .varint(3, snapshot.height)
                    .varint(4, 1); // IMAGE_FORMAT_RGB_565
            Proto entry = new Proto() // map<string, ImageResource> entry
                    .string(1, IMAGE_ID)
                    .message(2, new Proto().message(2, inline));
            resources.message(2, entry);
        }
        return resources.bytes();
    }

    /** LayoutElement with the given oneof field set (1 column, 3 box, 5 text, 6 image). */
    private static Proto element(int field, Proto inner) {
        return new Proto().message(field, inner);
    }

    private static Proto box(Proto size, Proto modifiers, Proto content) {
        return new Proto()
                .message(1, element(5, content))
                .message(2, size)
                .message(3, size)
                .message(4, new Proto().varint(1, 2))
                .message(5, new Proto().varint(1, 2))
                .message(6, modifiers);
    }

    private static Proto text(String value, float sizeSp, int argb, boolean bold, Proto modifiers) {
        Proto style = new Proto()
                .message(1, new Proto().float32(2, sizeSp)) // SpProp
                .message(4, color(argb));
        if (bold) style.message(5, new Proto().varint(1, 700)); // FONT_WEIGHT_BOLD
        Proto text = new Proto()
                .message(1, new Proto().string(1, value))
                .message(2, style);
        if (modifiers != null) text.message(3, modifiers);
        return text.message(4, new Proto().varint(1, 2)); // max_lines
    }

    private static Proto dp(float value) {
        return new Proto().float32(1, value);
    }

    private static Proto color(int argb) {
        return new Proto().varint(1, argb & 0xFFFFFFFFL);
    }

    /** Just enough of a protobuf encoder (and decoder) for the tile messages. */
    static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(int field, long value) {
            tag(field, 0);
            writeVarint(value);
            return this;
        }

        Proto float32(int field, float value) {
            tag(field, 5);
            int bits = Float.floatToIntBits(value);
            for (int i = 0; i < 4; i++) out.write(bits >> (8 * i));
            return this;
        }

        Proto string(int field, String value) {
            return raw(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto message(int field, Proto value) {
            return raw(field, value.bytes());
        }

        Proto raw(int field, byte[] data) {
            tag(field, 2);
            writeVarint(data.length);
            out.write(data, 0, data.length);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void tag(int field, int wireType) {
            writeVarint(((long) field << 3) | wireType);
        }

        private void writeVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }

        /** Returns the first string/bytes field with the given number, or null. */
        static String readString(byte[] data, int wanted) {
            int[] pos = {0};
            try {
                while (pos[0] < data.length) {
                    long key = readVarint(data, pos);
                    int field = (int) (key >>> 3);
                    int type = (int) (key & 7);
                    if (type == 0) {
                        readVarint(data, pos);
                    } else if (type == 1) {
                        pos[0] += 8;
                    } else if (type == 5) {
                        pos[0] += 4;
                    } else if (type == 2) {
                        int length = (int) readVarint(data, pos);
                        if (field == wanted) return new String(data, pos[0], length, StandardCharsets.UTF_8);
                        pos[0] += length;
                    } else {
                        return null;
                    }
                }
            } catch (RuntimeException ignored) {
            }
            return null;
        }

        private static long readVarint(byte[] data, int[] pos) {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = data[pos[0]++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalStateException("bad varint");
        }
    }
}
