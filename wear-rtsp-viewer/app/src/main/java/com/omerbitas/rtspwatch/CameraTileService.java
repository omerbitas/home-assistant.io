package com.omerbitas.rtspwatch;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wear OS tile with a single "Watch live" button that opens the camera.
 *
 * <p>The AndroidX Tiles library isn't used (to keep the app dependency-free), so this speaks the
 * tile provider binder protocol directly. It mirrors the AIDL interfaces in androidx.wear.tiles
 * (TileProvider, TileCallback, ResourcesCallback) and hand-encodes the small protobuf layout
 * (androidx.wear.tiles.proto.Tile, schema 1.0) that the system tile renderer expects.
 */
public class CameraTileService extends Service {

    private static final String PROVIDER = "androidx.wear.tiles.TileProvider";
    private static final String TILE_CALLBACK = "androidx.wear.tiles.TileCallback";
    private static final String RESOURCES_CALLBACK = "androidx.wear.tiles.ResourcesCallback";
    private static final String ACTION_BIND = "androidx.wear.tiles.action.BIND_TILE_PROVIDER";

    private static final int API_VERSION = 4;
    private static final int GET_API_VERSION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int ON_TILE_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int ON_RESOURCES_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int CALLBACK_UPDATE = IBinder.FIRST_CALL_TRANSACTION;

    private static final int TILE_DATA_VERSION = 1;
    private static final int RESOURCES_DATA_VERSION = 1;
    private static final String RESOURCES_VERSION = "1";

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
                skipParcelable(data);
                IBinder callback = data.readStrongBinder();
                if (callback != null) {
                    byte[] tile = buildTile(getPackageName(), LiveActivity.class.getName(),
                            getString(R.string.tile_title), getString(R.string.tile_button));
                    send(callback, TILE_CALLBACK, TILE_DATA_VERSION, tile);
                }
                return true;
            }
            if (code == ON_RESOURCES_REQUEST) {
                data.enforceInterface(PROVIDER);
                data.readInt(); // tile id
                skipParcelable(data);
                IBinder callback = data.readStrongBinder();
                if (callback != null) {
                    byte[] resources = new Proto().string(1, RESOURCES_VERSION).bytes();
                    send(callback, RESOURCES_CALLBACK, RESOURCES_DATA_VERSION, resources);
                }
                return true;
            }
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                return true; // add/remove/enter/leave/interaction events: nothing to do
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return intent != null && ACTION_BIND.equals(intent.getAction()) ? binder : null;
    }

    /** Skips an AIDL "in" ProtoParcelable argument: non-null flag, version, payload [, bundle]. */
    private static void skipParcelable(Parcel data) {
        if (data.readInt() == 0) return;
        int version = data.readInt();
        data.createByteArray();
        if (version >= 2) data.readBundle(CameraTileService.class.getClassLoader());
    }

    /** Calls the one-way updateTileData / updateResources method on the system's callback. */
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
    // Layout

    static byte[] buildTile(String packageName, String activity, String titleText, String buttonText) {
        // A round button in the middle of the tile; tapping anywhere opens the live view.
        Proto launch = new Proto().message(1, new Proto() // Action.launch_action
                .message(1, new Proto() // LaunchAction.android_activity
                        .string(1, packageName)
                        .string(2, activity)));
        Proto clickable = new Proto().string(1, "open").message(2, launch);

        Proto title = text(titleText, 14, 0xFFBDBDBD, false);
        Proto button = box(
                new Proto().message(1, dp(112)), // fixed 112dp circle (linear_dimension)
                new Proto()
                        .message(1, clickable)
                        .message(5, new Proto() // Background
                                .message(1, color(0xFF03A9F4))
                                .message(2, new Proto().message(1, dp(56)))), // Corner radius
                text("▶", 40, 0xFFFFFFFF, true));
        Proto subtitle = text(buttonText, 14, 0xFFFFFFFF, true);

        Proto column = new Proto()
                .message(1, element(5, title))
                .message(1, element(4, spacer(8)))
                .message(1, element(3, button))
                .message(1, element(4, spacer(8)))
                .message(1, element(5, subtitle))
                .message(2, new Proto().varint(1, 2)); // HORIZONTAL_ALIGN_CENTER

        Proto expanded = new Proto().message(2, new Proto()); // ExpandedDimensionProp
        Proto root = new Proto()
                .message(1, element(1, column))
                .message(2, expanded)
                .message(3, expanded)
                .message(4, new Proto().varint(1, 2)) // HORIZONTAL_ALIGN_CENTER
                .message(5, new Proto().varint(1, 2)) // VERTICAL_ALIGN_CENTER
                .message(6, new Proto().message(1, clickable));

        Proto layout = new Proto().message(1, element(3, root));
        Proto timeline = new Proto().message(1, new Proto().message(2, layout));
        return new Proto()
                .string(1, RESOURCES_VERSION)
                .message(2, timeline)
                .message(3, new Proto().varint(1, 1).varint(2, 0)) // schema 1.0
                .bytes();
    }

    /** LayoutElement with the given oneof field set (1 column, 3 box, 4 spacer, 5 text). */
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

    private static Proto text(String value, float sizeSp, int argb, boolean bold) {
        Proto style = new Proto()
                .message(1, new Proto().float32(2, sizeSp)) // SpProp
                .message(4, color(argb));
        if (bold) style.message(5, new Proto().varint(1, 700)); // FONT_WEIGHT_BOLD
        return new Proto()
                .message(1, new Proto().string(1, value))
                .message(2, style)
                .message(4, new Proto().varint(1, 2)); // max_lines
    }

    private static Proto spacer(float heightDp) {
        return new Proto().message(2, new Proto().message(1, dp(heightDp)));
    }

    private static Proto dp(float value) {
        return new Proto().float32(1, value);
    }

    private static Proto color(int argb) {
        return new Proto().varint(1, argb & 0xFFFFFFFFL);
    }

    /** Just enough of a protobuf encoder for the tile layout. */
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

        byte[] bytes() {
            return out.toByteArray();
        }

        private Proto raw(int field, byte[] data) {
            tag(field, 2);
            writeVarint(data.length);
            out.write(data, 0, data.length);
            return this;
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
    }
}
