package com.freddyq.diplay.chainlistener;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.lang.Process;
import java.text.SimpleDateFormat;
import java.util.*;

/** Foreground read-only log reader, never an OEM Binder client or media controller. */
public final class ListenerService extends Service {
    static final String START="dev.freddyq.medialightlistener.START";
    static volatile String status="未监听";
    private volatile Process process;
    private volatile int generation;
    private volatile boolean running;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("read-only", "只读诊断",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(1,new Notification.Builder(this,"read-only").setSmallIcon(R.drawable.listener_icon)
            .setContentTitle("原厂媒体灯光只读监听").setContentText("10分钟自动停止；不控制车辆或音频").setContentIntent(open).setOngoing(true).build());
        if (running) return START_NOT_STICKY;
        if (!"S311_MCA".equals(Build.MODEL) || Build.VERSION.SDK_INT!=28) { status="仅核验本车S311_MCA / Android9，已停止"; stopSelf(startId); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.READ_LOGS)!=PackageManager.PERMISSION_GRANTED) { status="READ_LOGS未获授权，已停止；未尝试读其他应用日志"; stopSelf(startId); return START_NOT_STICKY; }
        int ticket=++generation; running=true;
        String session=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        File directory=new File(getFilesDir(),"chain-session");
        // A single fixed set of bounded slots; a new manual run reuses them rather than accumulating sessions.
        Thread worker=new Thread(() -> record(directory,session,ticket,startId),"native-chain-read-only"); worker.setDaemon(true); worker.start();
        main.postDelayed(() -> { if (ticket==generation) stopSelf(startId); },LogSelection.DURATION_MILLIS);
        return START_NOT_STICKY;
    }
    private void record(File directory,String session,int ticket,int startId) {
        long read=0,saved=0,lastSummary=0;
        String finish="正常结束";
        try (BoundedLog output=new BoundedLog(directory)) {
            output.line("! start session="+session+" wall="+System.currentTimeMillis()+" elapsed="+SystemClock.elapsedRealtime()+" version=0.1 READ_LOGS=granted readOnly=true");
            String since=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.ROOT).format(new Date());
            Process current=new ProcessBuilder(LogSelection.command(since)).redirectErrorStream(true).start();
            if (ticket!=generation) { current.destroy(); return; }
            process=current;
            try (BufferedReader input=new BufferedReader(new InputStreamReader(current.getInputStream(),java.nio.charset.StandardCharsets.UTF_8),8192)) {
                String row;
                while (ticket==generation && (row=input.readLine())!=null) {
                    read++;
                    if (LogSelection.keep(row)) { output.line(LogSelection.redact(row)); saved++; }
                    long now=SystemClock.elapsedRealtime();
                    if (now-lastSummary>=5000) {
                        status="监听中：已读"+read+"条，保留"+saved+"条\n可关闭ADB后操作爱趣听；请记下操作时间。";
                        output.line("! progress elapsed="+now+" read="+read+" saved="+saved); lastSummary=now;
                    }
                }
            }
            current.destroy();
            if (ticket==generation) finish="日志进程结束";
            output.line("! stop wall="+System.currentTimeMillis()+" read="+read+" saved="+saved+" reason="+finish);
        } catch (Exception error) { finish="读取异常："+error.getClass().getSimpleName()+" "+error.getMessage(); }
        finally {
            if (ticket==generation) {
                status="已停止："+finish+"；已读"+read+"条，保留"+saved+"条\n日志在App私有目录chain-session。保留0条不代表链路成功。";
                running=false; main.post(() -> { if(ticket==generation) stopSelf(startId); });
            }
        }
    }
    @Override public void onDestroy() {
        generation++; running=false; main.removeCallbacksAndMessages(null);
        Process current=process; process=null; if(current!=null) current.destroy();
        if(status.startsWith("监听中")) status="监听已停止，日志已保留；重新开始会复用有界日志槽位。";
        stopForeground(true); super.onDestroy();
    }
}
