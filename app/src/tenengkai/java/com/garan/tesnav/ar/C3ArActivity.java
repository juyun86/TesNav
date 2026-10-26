package com.garan.tesnav.ar;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.widget.*;

/** All user configuration stays in 特会开; the fixed-name compatibility APK has no UI or credentials. */
public final class C3ArActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText endpoint, token;
    private final Runnable refresh = new Runnable() {
        public void run() {
            status.setText(C3ArCameraService.status + "\n网络帧：" + C3ArCameraService.frames +
                    " · 已交给高德：" + C3ArCameraService.published +
                    "\n" + (compatibleShim() ? "AR 兼容入口：已安装且签名匹配" : "AR 兼容入口：未安装或签名不匹配"));
            endpoint.setEnabled(!C3ArCameraService.running); token.setEnabled(!C3ArCameraService.running);
            if (C3ArCameraService.running) endpoint.setText(getSharedPreferences("c3-ar", 0).getString("endpoint", ""));
            handler.postDelayed(this, 1000);
        }
    };
    private boolean compatibleShim() {
        try {
            getPackageManager().getPackageInfo(C3ArCameraService.SHIM, 0);
            return getPackageManager().checkSignatures(getPackageName(), C3ArCameraService.SHIM) ==
                    android.content.pm.PackageManager.SIGNATURE_MATCH;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT < 27) { finish(); return; }
        SharedPreferences prefs = getSharedPreferences("c3-ar", 0);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(android.graphics.Color.WHITE);
        int padding = (int)(20 * getResources().getDisplayMetrics().density); body.setPadding(padding,padding,padding,padding);
        TextView title = new TextView(this); title.setText("C3 实景选道 · 高德原生 AR 接口"); title.setTextSize(22); body.addView(title);
        TextView help = new TextView(this); help.setText("打开特会开实景选道即可直连 C3，退出页面停止本页取流，无需中转 App。\n只读取已运行的相机，不启动相机或修改车辆控制；需要同帧车道投影数据。\n下方原生接口连接仅供高德兼容测试，最多20分钟，与特会开实景页独立。"); body.addView(help);
        endpoint = new EditText(this); endpoint.setSingleLine(); endpoint.setHint("http://C3局域网IP:8082/frames");
        endpoint.setText(prefs.getString("endpoint", "http://192.168.0.12:8082/frames")); body.addView(endpoint);
        token = new EditText(this); token.setSingleLine(); token.setHint("本次测试令牌（已保存时可留空）");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSaveEnabled(false); body.addView(token);
        status = new TextView(this); status.setTextSize(17); body.addView(status);
        Button start = new Button(this); start.setText("连接只读实景测试"); body.addView(start);
        start.setOnClickListener(view -> {
            if (C3ArCameraService.running) return;
            // Own real-scene display also works without the optional native AMap shim.
            String url = endpoint.getText().toString().trim();
            String key = token.getText().toString().trim();
            if (key.isEmpty()) key = prefs.getString("token", "");
            if (!ArFrameFormat.validEndpoint(url)) { toast("请输入局域网 IPv4 地址，包含端口和 /frames"); return; }
            if (key == null || !key.matches("[a-fA-F0-9]{48}")) { toast("请输入 C3 服务生成的 48 位测试令牌"); return; }
            prefs.edit().putString("endpoint", url).putString("token", key).apply(); token.setText("");
            startForegroundService(new Intent(this, C3ArCameraService.class).setAction("START"));
        });
        Button open = new Button(this); open.setText("返回导航页（先选高德车机来源，再进入 AR 校准）"); body.addView(open);
        Button road = new Button(this); road.setText("打开特会开实景选道（保留高德原生接口）"); body.addView(road);
        road.setOnClickListener(view -> {
            String url = endpoint.getText().toString().trim();
            String key = token.getText().toString().trim();
            if (key.isEmpty()) key = prefs.getString("token", "");
            if (!ArFrameFormat.validEndpoint(url) || key == null || !key.matches("[a-fA-F0-9]{48}")) {
                toast("请先配置有效 C3 地址和令牌"); return;
            }
            prefs.edit().putString("endpoint",url).putString("token",key).apply(); token.setText("");
            startActivity(new Intent(this, com.garan.tesnav.ui.ArRoadActivity.class));
        });
        open.setOnClickListener(view -> {
            if (!C3ArCameraService.running) { toast("请先连接实景测试"); return; }
            startActivity(new Intent(this, com.garan.tesnav.NavigationActivity.class));
        });
        Button stop = new Button(this); stop.setText("停止测试并释放画面"); body.addView(stop);
        stop.setOnClickListener(view -> {
            C3ArCameraService.status = "测试已停止";
            startService(new Intent(this, C3ArCameraService.class).setAction("STOP"));
        });
        Button forget = new Button(this); forget.setText("清除测试令牌"); body.addView(forget);
        forget.setOnClickListener(view -> {
            if (C3ArCameraService.running) { toast("请先停止测试"); return; }
            prefs.edit().remove("token").apply(); token.setText(""); toast("已清除");
        });
        setContentView(new ScrollView(this) {{ addView(body); }});
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
