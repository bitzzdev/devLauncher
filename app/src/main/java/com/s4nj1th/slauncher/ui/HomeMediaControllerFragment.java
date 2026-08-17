package com.s4nj1th.slauncher.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.s4nj1th.slauncher.media.MediaNotificationListener;
import com.s4nj1th.slauncher.R;

import java.util.ArrayList;
import java.util.List;

public class HomeMediaControllerFragment extends Fragment {
    private MediaSessionManager mediaSessionManager;
    private MediaController selectedController;
    private TextView titleView;
    private TextView artistView;
    private ImageButton playPause;
    private android.view.View next, prev;
    private MediaController.Callback controllerCallback;
    private SharedPreferences prefs;
    private String selectedPkg;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private final BroadcastReceiver sessionsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra("count", 0);
            if (count > 0) {
                if (selectedPkg == null) {
                    String pkg = intent.getStringExtra("pkg_0");
                    selectControllerForPackage(pkg);
                } else {
                    selectControllerForPackage(selectedPkg);
                }
            } else {
                selectedController = null;
                updateUi();
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaSessionManager = (MediaSessionManager) requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
        prefs = requireContext().getSharedPreferences("home_media", Context.MODE_PRIVATE);
        selectedPkg = prefs.getString("selected_pkg", null);
        prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if ("enabled".equals(key) || "selected_pkg".equals(key)) {
                    selectedPkg = sharedPreferences.getString("selected_pkg", null);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (selectedPkg != null) selectControllerForPackage(selectedPkg);
                            updateUi();
                        });
                    }
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        IntentFilter filter = new IntentFilter("com.s4nj1th.slauncher.MEDIA_SESSIONS");
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // use the 5-arg overload to ensure flags are honored at runtime
            requireContext().registerReceiver(sessionsReceiver, filter, null, null, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(sessionsReceiver, filter);
        }
    }

    @Override
    public View onCreateView(LayoutInflater li, ViewGroup vg, Bundle s) {
        View v = li.inflate(R.layout.fragment_home_media, vg, false);
        titleView = v.findViewById(R.id.media_title);
        artistView = v.findViewById(R.id.media_artist);
        playPause = v.findViewById(R.id.btn_play_pause);
        next = v.findViewById(R.id.btn_next);
        prev = v.findViewById(R.id.btn_prev);

        View.OnClickListener openAppListener = view -> openMediaApp();
        v.setOnClickListener(openAppListener);
        if (titleView != null) titleView.setOnClickListener(openAppListener);
        if (artistView != null) artistView.setOnClickListener(openAppListener);

        playPause.setOnClickListener(view -> {
            if (selectedController != null && selectedController.getTransportControls() != null) {
                int state = selectedController.getPlaybackState() != null ? selectedController.getPlaybackState().getState() : 0;
                if (state == android.media.session.PlaybackState.STATE_PLAYING) {
                    selectedController.getTransportControls().pause();
                } else {
                    selectedController.getTransportControls().play();
                }
                updatePlayPauseIcon();
            } else {
                openMediaApp();
            }
        });
        next.setOnClickListener(vv -> {
            if (selectedController != null && selectedController.getTransportControls() != null) {
                selectedController.getTransportControls().skipToNext();
            } else {
                openMediaApp();
            }
        });
        prev.setOnClickListener(vv -> {
            if (selectedController != null && selectedController.getTransportControls() != null) {
                selectedController.getTransportControls().skipToPrevious();
            } else {
                openMediaApp();
            }
        });

        // chooser and enable-listener controls moved to Settings screen.

        updateUi();
        return v;
    }

    private boolean launchPackageApp(Context context, String pkg) {
        if (context == null || pkg == null || pkg.trim().isEmpty()) return false;
        pkg = pkg.trim();

        // 1. System LauncherApps service (Primary method for launcher apps)
        try {
            android.content.pm.LauncherApps launcherApps = (android.content.pm.LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps != null) {
                List<android.content.pm.LauncherActivityInfo> activities = launcherApps.getActivityList(pkg, android.os.Process.myUserHandle());
                if (activities != null && !activities.isEmpty()) {
                    ComponentName comp = activities.get(0).getComponentName();
                    launcherApps.startMainActivity(comp, android.os.Process.myUserHandle(), null, null);
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. PackageManager launch intent fallback
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    public void openMediaApp() {
        Context context = getContext();
        if (context == null) return;

        if (!isNotificationListenerEnabled(context) && selectedController == null) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return;
            } catch (Exception ignored) {}
        }

        // Determine target package: active controller package first, then assigned package
        String targetPkg = null;
        if (selectedController != null && selectedController.getPackageName() != null) {
            targetPkg = selectedController.getPackageName();
        }
        if (targetPkg == null || targetPkg.trim().isEmpty()) {
            SharedPreferences sp = context.getSharedPreferences("home_media", Context.MODE_PRIVATE);
            targetPkg = sp.getString("selected_pkg", null);
        }

        // 1. Try launching target package using LauncherApps / Launch Intent
        if (targetPkg != null && !targetPkg.trim().isEmpty()) {
            if (launchPackageApp(context, targetPkg.trim())) {
                return;
            }
        }

        // 2. Try session activity if available
        if (selectedController != null) {
            android.app.PendingIntent sessionActivity = selectedController.getSessionActivity();
            if (sessionActivity != null) {
                try {
                    sessionActivity.send();
                    return;
                } catch (Exception ignored) {}
            }
        }

        // 3. Try any active session controllers if available
        try {
            ComponentName listenerComp = new ComponentName(context, MediaNotificationListener.class);
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(listenerComp);
            if (controllers != null && !controllers.isEmpty()) {
                for (MediaController c : controllers) {
                    if (c != null && c.getPackageName() != null) {
                        if (launchPackageApp(context, c.getPackageName())) {
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 4. Try standard media intent
        try {
            Intent musicIntent = new Intent(android.provider.MediaStore.INTENT_ACTION_MUSIC_PLAYER);
            musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(musicIntent);
            return;
        } catch (Exception ignored) {}

        try {
            Intent musicCategoryIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
            musicCategoryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(musicCategoryIntent);
            return;
        } catch (Exception ignored) {}

        android.widget.Toast.makeText(context, "No media app available", android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (prefs != null) {
            selectedPkg = prefs.getString("selected_pkg", null);
        }
        if (selectedPkg != null) selectControllerForPackage(selectedPkg);
        // Refresh UI to respect latest settings (show/hide when user toggles media widget)
        updateUi();
    }

    private void showChooserDialog() {
        try {
            ComponentName listenerComp = new ComponentName(requireContext(), MediaNotificationListener.class);
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(listenerComp);
            if (controllers == null || controllers.isEmpty()) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("No active media")
                        .setMessage("No active media sessions found. Start playback in an app first.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }

            final List<String> labels = new ArrayList<>();
            final List<String> pkgs = new ArrayList<>();
            PackageManager pm = requireContext().getPackageManager();
            for (MediaController c : controllers) {
                String pkg = c.getPackageName();
                String label = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    CharSequence appLabel = pm.getApplicationLabel(ai);
                    if (appLabel != null) label = appLabel.toString();
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                labels.add(label);
                pkgs.add(pkg);
            }

            CharSequence[] items = labels.toArray(new CharSequence[0]);
            new AlertDialog.Builder(requireContext())
                    .setTitle("Select media app")
                    .setItems(items, (dialog, which) -> {
                        String pkg = pkgs.get(which);
                        prefs.edit().putString("selected_pkg", pkg).apply();
                        selectedPkg = pkg;
                        selectControllerForPackage(pkg);
                    })
                    .show();
        } catch (SecurityException e) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Permission required")
                    .setMessage("Notification listener permission is required. Please enable it in settings.")
                    .setPositiveButton("Open Settings", (d, w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    public static boolean isNotificationListenerEnabled(Context context) {
        if (context == null) return false;
        String packageName = context.getPackageName();
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (flat != null) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && packageName.equals(cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureControllerCallback() {
        if (controllerCallback != null) return;
        controllerCallback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updatePlayPauseIcon();
                        updateUi();
                    });
                }
            }

            @Override
            public void onMetadataChanged(android.media.MediaMetadata metadata) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateUi());
                }
            }
        };
    }

    private void selectControllerForPackage(String pkg) {
        try {
            ComponentName listenerComp = new ComponentName(requireContext(), MediaNotificationListener.class);
            List<MediaController> controllers = null;
            try {
                controllers = mediaSessionManager.getActiveSessions(listenerComp);
            } catch (SecurityException ignored) {
                try {
                    controllers = mediaSessionManager.getActiveSessions(null);
                } catch (Exception ignored2) {}
            }

            MediaController newController = null;
            if (controllers != null && !controllers.isEmpty()) {
                // 1. Look for any controller currently playing audio
                for (MediaController c : controllers) {
                    if (c != null && c.getPlaybackState() != null &&
                            c.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                        newController = c;
                        break;
                    }
                }

                // 2. If no playing session, try matching preferred package if provided
                if (newController == null && pkg != null && !pkg.trim().isEmpty()) {
                    for (MediaController c : controllers) {
                        if (c != null && pkg.equals(c.getPackageName())) {
                            newController = c;
                            break;
                        }
                    }
                }

                // 3. Fallback to first available controller
                if (newController == null) {
                    newController = controllers.get(0);
                }
            }

            if (selectedController != newController) {
                if (selectedController != null && controllerCallback != null) {
                    try { selectedController.unregisterCallback(controllerCallback); } catch (Exception ignored) {}
                }
                selectedController = newController;
                if (selectedController != null) {
                    ensureControllerCallback();
                    try { selectedController.registerCallback(controllerCallback); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            selectedController = null;
        }
        updateUi();
    }

    private void updateUi() {
        if (titleView == null) return;
        boolean enabled = true;
        try {
            enabled = requireContext().getSharedPreferences("home_media", Context.MODE_PRIVATE).getBoolean("enabled", true);
        } catch (Exception ignored) { }

        View root = getView();
        if (root != null) {
            root.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }

        if (!enabled) {
            titleView.setText("No media");
            if (artistView != null) artistView.setText("");
            playPause.setEnabled(false);
            next.setEnabled(false);
            prev.setEnabled(false);
            return;
        }

        if (selectedController != null) {
            String titleStr = null;
            String artistStr = null;
            String albumStr = null;

            android.media.MediaMetadata md = selectedController.getMetadata();
            if (md != null) {
                CharSequence t = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                if (t == null || t.length() == 0) {
                    t = md.getDescription().getTitle();
                }
                if (t != null && t.length() > 0) {
                    titleStr = t.toString();
                }

                CharSequence a = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (a == null || a.length() == 0) {
                    a = md.getDescription().getSubtitle();
                }
                if (a != null && a.length() > 0) {
                    artistStr = a.toString();
                }

                CharSequence alb = md.getText(android.media.MediaMetadata.METADATA_KEY_ALBUM);
                if (alb != null && alb.length() > 0) {
                    albumStr = alb.toString();
                }
            }

            // Construct secondary line ("Artist - Album" or "Artist")
            String secondaryInfo = "";
            if (artistStr != null && !artistStr.trim().isEmpty()) {
                if (albumStr != null && !albumStr.trim().isEmpty() && !albumStr.trim().equalsIgnoreCase(artistStr.trim())) {
                    secondaryInfo = artistStr.trim() + " - " + albumStr.trim();
                } else {
                    secondaryInfo = artistStr.trim();
                }
            } else if (albumStr != null && !albumStr.trim().isEmpty()) {
                secondaryInfo = albumStr.trim();
            }

            if (titleStr == null || titleStr.trim().isEmpty()) {
                try {
                    PackageManager pm = requireContext().getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(selectedController.getPackageName(), 0);
                    titleStr = pm.getApplicationLabel(ai).toString();
                } catch (Exception e) {
                    titleStr = selectedController.getPackageName();
                }
            }

            titleView.setText(titleStr);
            if (artistView != null) {
                artistView.setText(secondaryInfo);
                artistView.setVisibility(secondaryInfo.isEmpty() ? View.GONE : View.VISIBLE);
            }
            playPause.setEnabled(true);
            next.setEnabled(true);
            prev.setEnabled(true);
            updatePlayPauseIcon();
        } else {
            if (!isNotificationListenerEnabled(requireContext())) {
                titleView.setText("Notification Access Required");
                if (artistView != null) {
                    artistView.setText("Tap to grant permission in Settings");
                    artistView.setVisibility(View.VISIBLE);
                }
            } else {
                String appName = null;
                if (selectedPkg != null && !selectedPkg.isEmpty()) {
                    try {
                        PackageManager pm = requireContext().getPackageManager();
                        ApplicationInfo ai = pm.getApplicationInfo(selectedPkg, 0);
                        appName = pm.getApplicationLabel(ai).toString();
                    } catch (Exception ignored) {}
                }
                if (appName != null && !appName.isEmpty()) {
                    titleView.setText(appName);
                    if (artistView != null) {
                        artistView.setText("Tap to open media app");
                        artistView.setVisibility(View.VISIBLE);
                    }
                } else {
                    titleView.setText("No media");
                    if (artistView != null) {
                        artistView.setText("Tap to select media app");
                        artistView.setVisibility(View.VISIBLE);
                    }
                }
            }
            playPause.setEnabled(true);
            next.setEnabled(true);
            prev.setEnabled(true);
            updatePlayPauseIcon();
        }
    }

    private void updatePlayPauseIcon() {
        if (playPause == null) return;
        int state = selectedController != null && selectedController.getPlaybackState() != null
                ? selectedController.getPlaybackState().getState() : 0;
        if (state == android.media.session.PlaybackState.STATE_PLAYING) {
            playPause.setImageResource(R.drawable.ic_pause);
        } else {
            playPause.setImageResource(R.drawable.ic_play_arrow);
        }
    }

    @Override
    public void onDestroy() {
        requireContext().unregisterReceiver(sessionsReceiver);
        if (prefs != null && prefListener != null) {
            try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Exception ignored) {}
        }
        if (selectedController != null && controllerCallback != null) {
            try { selectedController.unregisterCallback(controllerCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
