package jp.local.touchlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 常駐サービス。
 * - 小さな鍵ボタン（フローティング）を表示し、タップで全画面ロック。
 * - ロック中は全画面オーバーレイが全タッチを吸収する。
 * - 解除は音量の変化を検知して行う（音量ボタン上下どちらでも）。
 *   音量が最大/最小で変化しない場合に備え、右下の鍵マークを3秒長押しでも解除できる。
 */
public class LockService extends Service {

    private static final String CHANNEL_ID = "touchlock";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager wm;
    private TextView keyButton;
    private WindowManager.LayoutParams keyParams;
    private View lockOverlay;
    private boolean locked = false;

    private AudioManager audioManager;
    private int lastVolume = -1;
    private ContentObserver volumeObserver;
    private BroadcastReceiver volumeReceiver;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        startForegroundWithNotification();
        showKeyButton();
        registerVolumeWatchers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "UNLOCK".equals(intent.getAction())) {
            unlock();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unlock();
        if (keyButton != null) {
            try { wm.removeView(keyButton); } catch (Exception ignored) {}
            keyButton = null;
        }
        unregisterVolumeWatchers();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 通知 ----------

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "タッチロック", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent unlockIntent = new Intent(this, LockService.class).setAction("UNLOCK");
        PendingIntent pi = PendingIntent.getService(this, 0, unlockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("タッチロック待機中")
                .setContentText("鍵ボタンでロック / 解除は音量ボタン")
                .setSmallIcon(R.drawable.ic_launcher)
                .addAction(0, "ロック解除", pi)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    // ---------- 鍵ボタン ----------

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void showKeyButton() {
        keyButton = new TextView(this);
        keyButton.setText("🔓");
        keyButton.setTextSize(26);
        keyButton.setGravity(Gravity.CENTER);
        keyButton.setBackgroundColor(Color.argb(140, 40, 40, 40));
        int size = dp(56);
        keyParams = new WindowManager.LayoutParams(
                size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        keyParams.gravity = Gravity.BOTTOM | Gravity.END;
        keyParams.x = dp(16);
        keyParams.y = dp(120);

        keyButton.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private long downAt;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = keyParams.x;
                        startY = keyParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        downAt = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                        // Gravity BOTTOM|END 基準なので符号が逆
                        keyParams.x = startX - dx;
                        keyParams.y = startY - dy;
                        wm.updateViewLayout(keyButton, keyParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && System.currentTimeMillis() - downAt < 400) {
                            lock();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(keyButton, keyParams);
    }

    // ---------- ロック / 解除 ----------

    private void lock() {
        if (locked) return;
        locked = true;
        lastVolume = currentVolume();

        FrameLayout overlay = new FrameLayout(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return true; // 全タッチを吸収する
            }
        };
        overlay.setBackgroundColor(Color.argb(24, 0, 0, 0));

        TextView mark = new TextView(this);
        mark.setText("🔒");
        mark.setTextSize(22);
        mark.setGravity(Gravity.CENTER);
        mark.setBackgroundColor(Color.argb(120, 40, 40, 40));
        int msize = dp(48);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(msize, msize);
        mlp.gravity = Gravity.BOTTOM | Gravity.END;
        mlp.setMargins(0, 0, dp(16), dp(16));
        overlay.addView(mark, mlp);

        // 非常用解除: 鍵マークを3秒長押し
        mark.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                handler.postDelayed(unlockRunnable, 3000);
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(unlockRunnable);
            }
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lockOverlay = overlay;
        wm.addView(lockOverlay, lp);
        if (keyButton != null) keyButton.setVisibility(View.GONE);
        toast("ロックしました（解除は音量ボタン）");
    }

    private final Runnable unlockRunnable = this::unlock;

    private void unlock() {
        if (!locked) return;
        locked = false;
        handler.removeCallbacks(unlockRunnable);
        if (lockOverlay != null) {
            try { wm.removeView(lockOverlay); } catch (Exception ignored) {}
            lockOverlay = null;
        }
        if (keyButton != null) keyButton.setVisibility(View.VISIBLE);
        toast("ロック解除しました");
    }

    // ---------- 音量ボタン検知 ----------

    private int currentVolume() {
        return audioManager != null
                ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : -1;
    }

    private void onVolumeMaybeChanged() {
        if (!locked) return;
        int v = currentVolume();
        if (lastVolume >= 0 && v != lastVolume) {
            unlock();
        }
        lastVolume = v;
    }

    private void registerVolumeWatchers() {
        // 方法1: システムの音量変更ブロードキャスト
        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onVolumeMaybeChanged();
            }
        };
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, f);
        }
        // 方法2: 設定値の変更監視（ブロードキャストが来ない機種向け）
        volumeObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                onVolumeMaybeChanged();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver);
    }

    private void unregisterVolumeWatchers() {
        if (volumeReceiver != null) {
            try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
            volumeReceiver = null;
        }
        if (volumeObserver != null) {
            getContentResolver().unregisterContentObserver(volumeObserver);
            volumeObserver = null;
        }
    }

    // ---------- その他 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
