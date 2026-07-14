package com.enzo.podsguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_BLUETOOTH = 1201;
    private static final int REQ_NOTIFICATIONS = 1202;

    private SharedPreferences prefs;
    private Switch protectionSwitch;
    private Switch bluetoothOnlySwitch;
    private RadioGroup delayGroup;
    private Button accessButton;
    private Button pauseOnceButton;
    private Button backgroundButton;
    private TextView statusText;
    private TextView protectionState;
    private TextView bypassText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable bypassTicker = new Runnable() {
        @Override
        public void run() {
            updateBypassText();
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = Prefs.get(this);
        protectionSwitch = findViewById(R.id.protectionSwitch);
        bluetoothOnlySwitch = findViewById(R.id.bluetoothOnlySwitch);
        delayGroup = findViewById(R.id.delayGroup);
        accessButton = findViewById(R.id.accessButton);
        pauseOnceButton = findViewById(R.id.pauseOnceButton);
        backgroundButton = findViewById(R.id.backgroundButton);
        statusText = findViewById(R.id.statusText);
        protectionState = findViewById(R.id.protectionState);
        bypassText = findViewById(R.id.bypassText);

        boolean enabled = prefs.getBoolean(Prefs.ENABLED, false);
        protectionSwitch.setChecked(enabled);
        bluetoothOnlySwitch.setChecked(prefs.getBoolean(Prefs.ONLY_BLUETOOTH, true));

        int delay = prefs.getInt(Prefs.DELAY_MS, 120);
        if (delay <= 140) {
            delayGroup.check(R.id.delayFast);
        } else if (delay >= 500) {
            delayGroup.check(R.id.delaySafe);
        } else {
            delayGroup.check(R.id.delayNormal);
        }

        protectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(Prefs.ENABLED, isChecked).apply();
            protectionState.setText(isChecked ? R.string.status_enabled : R.string.status_disabled);

            if (isChecked) {
                ensureNotificationPermission();
                ensureBluetoothPermission();
                GuardService.start(this);
                PodsGuardListener.requestRefresh(this);
                if (!isNotificationAccessEnabled()) {
                    Toast.makeText(this, R.string.status_need_access, Toast.LENGTH_LONG).show();
                }
            } else {
                GuardService.stop(this);
            }
            updateAccessState();
        });

        bluetoothOnlySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(Prefs.ONLY_BLUETOOTH, isChecked).apply();
            if (isChecked) ensureBluetoothPermission();
        });

        delayGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int delayMs = 260;
            if (checkedId == R.id.delayFast) delayMs = 80;
            if (checkedId == R.id.delaySafe) delayMs = 650;
            prefs.edit().putInt(Prefs.DELAY_MS, delayMs).apply();
        });

        accessButton.setOnClickListener(v -> openNotificationAccessSettings());

        pauseOnceButton.setOnClickListener(v -> {
            long until = System.currentTimeMillis() + 15_000L;
            prefs.edit().putLong(Prefs.BYPASS_UNTIL, until).apply();
            PodsGuardListener.pauseCurrentPlayerOnce();
            updateBypassText();
            Toast.makeText(this,
                    "Автопродолжение временно отключено на 15 секунд",
                    Toast.LENGTH_SHORT).show();
        });

        backgroundButton.setOnClickListener(v -> openAppSettings());

        protectionState.setText(enabled ? R.string.status_enabled : R.string.status_disabled);
        ensureBluetoothPermission();
        if (enabled) {
            ensureNotificationPermission();
            GuardService.start(this);
            PodsGuardListener.requestRefresh(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessState();
        handler.removeCallbacks(bypassTicker);
        handler.post(bypassTicker);
        PodsGuardListener.requestRefresh(this);
        if (prefs.getBoolean(Prefs.ENABLED, false)) GuardService.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(bypassTicker);
    }

    private void updateAccessState() {
        boolean granted = isNotificationAccessEnabled();
        boolean enabled = prefs.getBoolean(Prefs.ENABLED, false);

        if (granted && enabled) {
            accessButton.setText(R.string.access_granted);
            statusText.setText(R.string.status_ready_v11);
            statusText.setTextColor(getColor(R.color.green_dark));
            statusText.setBackgroundResource(R.drawable.bg_status_ok);
        } else if (granted) {
            accessButton.setText(R.string.access_granted);
            statusText.setText(R.string.status_enable_switch);
            statusText.setTextColor(getColor(R.color.warning_text));
            statusText.setBackgroundResource(R.drawable.bg_status_warn);
        } else {
            accessButton.setText(R.string.open_access);
            statusText.setText(R.string.status_need_access);
            statusText.setTextColor(getColor(R.color.warning_text));
            statusText.setBackgroundResource(R.drawable.bg_status_warn);
        }
    }

    private boolean isNotificationAccessEnabled() {
        String enabledListeners = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabledListeners)) return false;

        String packageName = getPackageName();
        String component = new ComponentName(this, PodsGuardListener.class).flattenToString();
        return enabledListeners.contains(packageName) || enabledListeners.contains(component);
    }

    private void openNotificationAccessSettings() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                intent.putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new ComponentName(this, PodsGuardListener.class).flattenToString());
            } else {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (!bluetoothOnlySwitch.isChecked()) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_bluetooth_title)
                .setMessage(R.string.permission_bluetooth_text)
                .setPositiveButton(R.string.grant, (dialog, which) ->
                        requestPermissions(
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                                REQ_BLUETOOTH))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private void updateBypassText() {
        long until = prefs.getLong(Prefs.BYPASS_UNTIL, 0L);
        long remainingMs = until - System.currentTimeMillis();
        if (remainingMs > 0L) {
            long seconds = (remainingMs + 999L) / 1000L;
            bypassText.setText(getString(R.string.bypass_active, seconds));
            bypassText.setVisibility(View.VISIBLE);
        } else {
            bypassText.setVisibility(View.GONE);
        }
    }
}
