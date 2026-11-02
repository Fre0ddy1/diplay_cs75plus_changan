package com.freddyq.diplay.chainlistener;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private TextView state;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() { public void run() { state.setText(ListenerService.status); handler.postDelayed(this,1000); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,20,24,20); setContentView(root);
        TextView title=new TextView(this); title.setTextSize(24); title.setText("原厂媒体灯光只读监听 0.1"); root.addView(title);
        TextView help=new TextView(this); help.setText("只记录原厂现有媒体、取色和灯光下发日志。启动App不开始监听。\n需先经ADB明确授权READ_LOGS；点击开始后可关闭ADB、手动操作爱趣听。10分钟自动停止，日志总上限16MiB。\n不播放、不抢音频焦点、不查询或写入车控属性、不改原车设置、不访问网络。重新开始会覆盖上一轮日志；取回前不要重新开始或清数据。"); root.addView(help);
        Button start=new Button(this); start.setText("开始只读监听（10分钟）"); root.addView(start);
        start.setOnClickListener(view -> startForegroundService(new Intent(this,ListenerService.class).setAction(ListenerService.START)));
        Button stop=new Button(this); stop.setText("停止监听"); root.addView(stop); stop.setOnClickListener(view -> stopService(new Intent(this,ListenerService.class)));
        state=new TextView(this); state.setTextIsSelectable(true); root.addView(state);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
