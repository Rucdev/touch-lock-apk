package jp.local.touchlock;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("タッチロック");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\nYouTubeなどを再生したまま画面をロックして、\n赤ちゃんのタッチで再生が止まるのを防ぎます。\n\n"
                + "使い方:\n"
                + "1. 「ロック開始」を押すと、小さな鍵ボタンが画面に出ます\n"
                + "2. YouTubeで動画を再生して、鍵ボタンをタップ → ロック\n"
                + "3. 解除は 音量ボタン（上下どちらでも）を押すだけ\n");
        desc.setTextSize(16);
        root.addView(desc);

        Button start = new Button(this);
        start.setText("ロック開始（鍵ボタンを表示）");
        start.setOnClickListener(v -> startLock());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("終了（鍵ボタンを消す）");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, LockService.class));
            Toast.makeText(this, "終了しました", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        setContentView(root);
    }

    private void startLock() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "「他のアプリの上に表示」を許可してください", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Intent intent = new Intent(this, LockService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "鍵ボタンを表示しました。YouTubeを開いて再生してから鍵をタップ", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }
}
