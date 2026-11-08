package com.gathin.tvhome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Top-bar button that connects or disconnects one paired Bluetooth audio device (config: bt_device_name).
 * BluetoothA2dp.connect/disconnect are hidden APIs; reflection works on Fire OS 5 (API 22), which has no
 * hidden-API restrictions. The device must already be paired in Fire TV Settings.
 */
class BudsToggle {
    interface IconSetter { void set(int icon); }

    private final Context ctx;
    private final TextView button;
    private final IconSetter setIcon;
    private final String name;
    private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    private BluetoothA2dp a2dp;

    private final BroadcastReceiver stateChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    BudsToggle(Context ctx, TextView button, IconSetter setIcon) {
        this.ctx = ctx;
        this.button = button;
        this.setIcon = setIcon;
        this.name = ctx.getString(R.string.bt_device_name);
        button.setContentDescription(name);
        button.setOnClickListener(v -> toggle());
        if (adapter == null) return;
        adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int p, BluetoothProfile proxy) { a2dp = (BluetoothA2dp) proxy; refresh(); }
            @Override public void onServiceDisconnected(int p) { a2dp = null; }
        }, BluetoothProfile.A2DP);
    }

    void resume() {
        IntentFilter f = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        ctx.registerReceiver(stateChanged, f);
        refresh();
    }

    void pause() {
        ctx.unregisterReceiver(stateChanged);
    }

    private BluetoothDevice device() {
        if (adapter == null || !adapter.isEnabled()) return null;
        for (BluetoothDevice d : adapter.getBondedDevices())
            if (d.getName() != null && d.getName().toLowerCase().contains(name.toLowerCase())) return d;
        return null;
    }

    private boolean connected(BluetoothDevice d) {
        return a2dp != null && d != null && a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
    }

    private void refresh() {
        setIcon.set(connected(device()) ? R.drawable.ic_bluetooth_connected : R.drawable.ic_bluetooth);
    }

    private void toggle() {
        if (adapter == null) return;
        if (!adapter.isEnabled()) {
            adapter.enable();
            toast(R.string.bt_turning_on);
            return;
        }
        BluetoothDevice d = device();
        if (d == null) { toast(R.string.bt_not_paired); return; }
        if (a2dp == null) return;
        boolean on = connected(d);
        try {
            BluetoothA2dp.class.getMethod(on ? "disconnect" : "connect", BluetoothDevice.class).invoke(a2dp, d);
            toast(on ? R.string.bt_disconnecting : R.string.bt_connecting);
        } catch (Exception e) {
            toast(R.string.bt_failed);
        }
    }

    private void toast(int msg) {
        Toast.makeText(ctx, ctx.getString(msg, name), Toast.LENGTH_SHORT).show();
    }
}
