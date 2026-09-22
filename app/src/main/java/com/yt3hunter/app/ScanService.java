package com.yt3hunter.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ScanService extends Service {
    public static final String PREFS="scan_state";
    public static final String ACTION_START="com.yt3hunter.START";
    public static final String ACTION_PAUSE="com.yt3hunter.PAUSE";
    public static final String ACTION_RESUME="com.yt3hunter.RESUME";
    public static final String ACTION_STOP="com.yt3hunter.STOP";

    private static final int NOTIF_ID=73;
    private static final String CHANNEL="yt3_scan";

    private volatile boolean scanning=false;
    private volatile boolean paused=false;

    private Thread engineThread;
    private PowerManager.WakeLock wakeLock;
    private Db db;
    private SharedPreferences prefs;

    private List<String> handles;
    private final Object cursorLock=new Object();
    private AtomicInteger nextIndex;
    private int safeCursor;
    private final TreeSet<Integer> completed=new TreeSet<>();
    private AtomicInteger takenCount=new AtomicInteger();
    private final AtomicInteger notificationTick=new AtomicInteger();

    private static final String[][] PROVIDERS={
        {"youtube","https://www.youtube.com/@"},
        {"f5","https://invidious.f5.si/api/v1/resolveurl?url="},
        {"nadeko","https://inv.nadeko.net/api/v1/resolveurl?url="},
        {"tie","https://invidious.tiekoetter.com/api/v1/resolveurl?url="}
    };

    private static final ConcurrentHashMap<String,Gate> GATES=new ConcurrentHashMap<>();

    static class Gate {
        long lastStart=0;
        long backoffUntil=0;
        int fail=0;

        synchronized void before() throws InterruptedException {
            while(true){
                long now=System.currentTimeMillis();
                long ms=Math.max(backoffUntil-now,110-(now-lastStart));
                if(ms<=0){
                    lastStart=now;
                    return;
                }
                wait(ms);
            }
        }

        synchronized void ok(){
            fail=Math.max(0,fail-1);
            if(fail==0) backoffUntil=0;
            notifyAll();
        }

        synchronized void failed(boolean hard){
            fail=Math.min(8,fail+(hard?2:1));
            long ms=Math.min(hard?45000:15000,(long)(1000*Math.pow(1.65,fail)));
            backoffUntil=Math.max(backoffUntil,System.currentTimeMillis()+ms);
            notifyAll();
        }
    }

    static class Verdict {
        static final int TAKEN=1;
        static final int FREE=2;
        static final int UNKNOWN=3;

        final int type;
        final String reason;

        Verdict(int t,String r){
            type=t;
            reason=r;
        }
    }

    @Override public void onCreate(){
        super.onCreate();
        db=new Db(this);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        createChannel();

        for(String[] p:PROVIDERS){
            GATES.putIfAbsent(p[0],new Gate());
        }
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_START:intent.getAction();

        if(ACTION_STOP.equals(action)){
            stopScan();
            return START_NOT_STICKY;
        }

        if(ACTION_PAUSE.equals(action)){
            paused=true;
            prefs.edit().putBoolean("paused",true).apply();
            releaseWake();
            updateNotification("Пауза");
            return START_STICKY;
        }

        if(ACTION_RESUME.equals(action)){
            paused=false;
            prefs.edit().putBoolean("paused",false).apply();
            acquireWake();
            updateNotification("Продолжаю");
            startEngine();
            return START_STICKY;
        }

        scanning=true;
        paused=prefs.getBoolean("paused",false);
        prefs.edit().putBoolean("scanning",true).apply();

        startForeground(NOTIF_ID,buildNotification("Запуск…"));

        if(!paused) acquireWake();
        startEngine();

        return START_STICKY;
    }

    private synchronized void startEngine(){
        if(engineThread!=null && engineThread.isAlive()) return;

        engineThread=new Thread(this::engineLoop,"YT3-engine");
        engineThread.start();
    }

    private void engineLoop(){
        while(scanning){
            try{
                handles=HandleGenerator.build();
                prefs.edit().putInt("total",handles.size()).apply();

                String phase=prefs.getString("phase","initial");

                if("done".equals(phase)){
                    completeAll();
                    return;
                }

                if("initial".equals(phase)){
                    runInitial();
                }

                if(scanning){
                    runRetryLoop();
                }

                return;
            }catch(Throwable e){
                prefs.edit().putString("last_error",String.valueOf(e)).apply();
                updateNotification("Ошибка сети/движка — повтор через 15 сек");

                try{
                    Thread.sleep(15000);
                }catch(InterruptedException ignored){
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void runInitial() throws InterruptedException {
        safeCursor=Math.min(prefs.getInt("cursor",0),handles.size());
        nextIndex=new AtomicInteger(safeCursor);
        takenCount.set(prefs.getInt("taken",0));
        completed.clear();

        int workers=4;
        ExecutorService pool=Executors.newFixedThreadPool(workers);
        CountDownLatch latch=new CountDownLatch(workers);

        for(int w=0;w<workers;w++){
            pool.execute(()->{
                try{
                    while(scanning){
                        waitIfPaused();

                        int idx=nextIndex.getAndIncrement();
                        if(idx>=handles.size()) break;

                        String h=handles.get(idx);
                        prefs.edit().putString("current",h).apply();

                        Verdict verdict=verifyStrict(h);
                        applyInitial(h,verdict);
                        markCompleted(idx);
                    }
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }finally{
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdownNow();

        if(!scanning) return;

        prefs.edit()
            .putInt("cursor",handles.size())
            .putInt("checked",handles.size())
            .putString("phase","retry")
            .apply();

        updateNotification("Первый проход завершён");
    }

    private void markCompleted(int idx){
        synchronized(cursorLock){
            completed.add(idx);

            while(completed.remove(safeCursor)){
                safeCursor++;
            }

            prefs.edit()
                .putInt("cursor",safeCursor)
                .putInt("checked",safeCursor)
                .apply();
        }

        if(notificationTick.incrementAndGet()%15==0){
            updateNotification(null);
        }
    }

    private void applyInitial(String handle,Verdict verdict){
        if(verdict.type==Verdict.TAKEN){
            takenCount.incrementAndGet();
            db.remove(handle);
            prefs.edit().putInt("taken",takenCount.get()).apply();
            return;
        }

        if(verdict.type==Verdict.FREE){
            db.putFree(handle,verdict.reason);
            return;
        }

        db.putUnknown(handle,verdict.reason);
    }

    private void runRetryLoop() throws InterruptedException {
        prefs.edit().putString("phase","retry").apply();

        while(scanning){
            waitIfPaused();

            List<String> pending=db.unknownHandles();

            if(pending.isEmpty()){
                completeAll();
                return;
            }

            updateNotification("Перепроверка сомнительных: "+pending.size());

            for(String handle:pending){
                if(!scanning) return;

                waitIfPaused();
                prefs.edit().putString("current",handle).apply();

                Verdict verdict=verifyStrict(handle);

                if(verdict.type==Verdict.TAKEN){
                    db.remove(handle);
                    takenCount.incrementAndGet();
                    prefs.edit().putInt("taken",takenCount.get()).apply();
                }else if(verdict.type==Verdict.FREE){
                    db.putFree(handle,verdict.reason);
                }else{
                    db.putUnknown(handle,verdict.reason);
                }

                if(notificationTick.incrementAndGet()%10==0){
                    updateNotification(null);
                }
            }

            if(db.count(Db.UNKNOWN)>0){
                Thread.sleep(30000);
            }
        }
    }

    private Verdict verifyStrict(String handle){
        Verdict first=verifyPass(handle);

        if(first.type!=Verdict.FREE){
            return first;
        }

        try{
            Thread.sleep(650+new Random().nextInt(350));
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return new Verdict(Verdict.UNKNOWN,"interrupted");
        }

        Verdict second=verifyPass(handle);

        if(second.type==Verdict.FREE){
            return new Verdict(Verdict.FREE,first.reason+" | repeat ok");
        }

        if(second.type==Verdict.TAKEN){
            return second;
        }

        return new Verdict(Verdict.UNKNOWN,"repeat: "+second.reason);
    }

    private Verdict verifyPass(String handle){
        ArrayList<String> freeBy=new ArrayList<>();
        ArrayList<String> unknownBy=new ArrayList<>();

        Verdict youtube=checkYouTube(handle);

        if(youtube.type==Verdict.TAKEN){
            return youtube;
        }

        if(youtube.type==Verdict.FREE){
            freeBy.add("YouTube");
        }else{
            unknownBy.add("YouTube:"+youtube.reason);
        }

        for(int i=1;i<PROVIDERS.length;i++){
            Verdict v=checkInvidious(PROVIDERS[i][0],PROVIDERS[i][1],handle);

            if(v.type==Verdict.TAKEN){
                return v;
            }

            if(v.type==Verdict.FREE){
                freeBy.add(PROVIDERS[i][0]);
            }else{
                unknownBy.add(PROVIDERS[i][0]+":"+v.reason);
            }
        }

        if(freeBy.size()>=2){
            return new Verdict(Verdict.FREE,"free: "+String.join(",",freeBy));
        }

        return new Verdict(
            Verdict.UNKNOWN,
            "need 2 confirmations; "+String.join(" | ",unknownBy)
        );
    }

    private Verdict checkYouTube(String handle){
        Gate gate=GATES.get("youtube");

        try{
            gate.before();

            String encoded=URLEncoder.encode(handle,"UTF-8");
            URL url=new URL(PROVIDERS[0][1]+encoded+"?hl=en");

            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
            );

            int code=c.getResponseCode();
            String text=read(c,code);
            String low=text.toLowerCase(Locale.ROOT);

            if(code==429){
                gate.failed(true);
                return new Verdict(Verdict.UNKNOWN,"429");
            }

            if(code>=500){
                gate.failed(false);
                return new Verdict(Verdict.UNKNOWN,"http "+code);
            }

            if(code==404 || code==410){
                gate.ok();
                return new Verdict(Verdict.FREE,"YouTube "+code);
            }

            if(
                low.contains("\"channelid\":\"uc") ||
                low.contains("\"externalid\":\"uc") ||
                low.contains("\"browseid\":\"uc") ||
                low.contains("youtube.com/channel/uc") ||
                low.contains("\"canonicalbaseurl\":\"/@")
            ){
                gate.ok();
                return new Verdict(Verdict.TAKEN,"YouTube channel metadata");
            }

            if(
                low.contains("this page isn't available") ||
                low.contains("this page isn’t available") ||
                low.contains("this channel does not exist")
            ){
                gate.ok();
                return new Verdict(Verdict.FREE,"YouTube not found page");
            }

            gate.ok();
            return new Verdict(Verdict.UNKNOWN,"ambiguous "+code);

        }catch(Exception e){
            gate.failed(false);
            return new Verdict(Verdict.UNKNOWN,e.getClass().getSimpleName());
        }
    }

    private Verdict checkInvidious(String key,String base,String handle){
        Gate gate=GATES.get(key);

        try{
            gate.before();

            String target="https://www.youtube.com/@"+handle;
            String encoded=URLEncoder.encode(target,"UTF-8");
            URL url=new URL(base+encoded);

            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setRequestProperty("Accept","application/json");

            int code=c.getResponseCode();
            String text=read(c,code);
            String low=text.toLowerCase(Locale.ROOT);
            String compact=low.replace(" ","").replace("\n","").replace("\t","");

            if(code==429){
                gate.failed(true);
                return new Verdict(Verdict.UNKNOWN,"429");
            }

            if(code>=500){
                gate.failed(false);
                return new Verdict(Verdict.UNKNOWN,"http "+code);
            }

            if(
                compact.contains("\"ucid\":\"uc") ||
                compact.contains("\"browseid\":\"uc") ||
                compact.contains("\"channelid\":\"uc") ||
                compact.contains("\"authorid\":\"uc")
            ){
                gate.ok();
                return new Verdict(Verdict.TAKEN,key+" resolved channel");
            }

            if(
                code==404 ||
                (
                    code==400 &&
                    (
                        low.contains("not found") ||
                        low.contains("invalid") ||
                        low.contains("could not resolve") ||
                        low.contains("unable to resolve") ||
                        low.contains("does not exist") ||
                        low.contains("no matching")
                    )
                )
            ){
                gate.ok();
                return new Verdict(Verdict.FREE,key+" unresolved");
            }

            gate.ok();
            return new Verdict(Verdict.UNKNOWN,"http "+code);

        }catch(Exception e){
            gate.failed(false);
            return new Verdict(Verdict.UNKNOWN,e.getClass().getSimpleName());
        }
    }

    private String read(HttpURLConnection c,int code) throws IOException {
        InputStream in=code>=400?c.getErrorStream():c.getInputStream();

        if(in==null) return "";

        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];
        int n;
        int total=0;

        while((n=in.read(buffer))>0 && total<1800000){
            out.write(buffer,0,n);
            total+=n;
        }

        in.close();

        return out.toString(StandardCharsets.UTF_8.name());
    }

    private void waitIfPaused() throws InterruptedException {
        while(scanning && paused){
            Thread.sleep(500);
        }
    }

    private void completeAll(){
        scanning=false;
        paused=false;

        prefs.edit()
            .putBoolean("scanning",false)
            .putBoolean("paused",false)
            .putString("phase","done")
            .putString("current","")
            .apply();

        releaseWake();

        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID,buildNotification("Готово: вся очередь проверена, сомнительных 0"));

        stopForeground(false);
        stopSelf();
    }

    private void stopScan(){
        scanning=false;
        paused=false;

        prefs.edit()
            .putBoolean("scanning",false)
            .putBoolean("paused",false)
            .apply();

        releaseWake();
        stopForeground(true);
        stopSelf();
    }

    private void acquireWake(){
        if(wakeLock!=null && wakeLock.isHeld()) return;

        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YT3Hunter:Scan"
        );

        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWake(){
        try{
            if(wakeLock!=null && wakeLock.isHeld()){
                wakeLock.release();
            }
        }catch(Exception ignored){}
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(
                CHANNEL,
                "YT3 background scan",
                NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("Keeps the complete handle scan running in the background");

            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String override){
        Intent open=new Intent(this,MainActivity.class);

        PendingIntent content=PendingIntent.getActivity(
            this,
            1,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
        );

        String pauseAction=paused?ACTION_RESUME:ACTION_PAUSE;

        PendingIntent pauseIntent=PendingIntent.getService(
            this,
            2,
            new Intent(this,ScanService.class).setAction(pauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent stopIntent=PendingIntent.getService(
            this,
            3,
            new Intent(this,ScanService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
        );

        int checked=prefs.getInt("checked",0);
        int total=prefs.getInt("total",0);
        int free=db==null?0:db.count(Db.FREE);
        int unknown=db==null?0:db.count(Db.UNKNOWN);

        String text=override!=null
            ? override
            : checked+"/"+total+" • свободно "+free+" • сомнительно "+unknown;

        Notification.Builder builder;

        if(Build.VERSION.SDK_INT>=26){
            builder=new Notification.Builder(this,CHANNEL);
        }else{
            builder=new Notification.Builder(this);
        }

        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("YT3 Hunter — "+(paused?"пауза":"проверка идёт"))
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(scanning)
            .setOnlyAlertOnce(true)
            .addAction(0,paused?"Продолжить":"Пауза",pauseIntent)
            .addAction(0,"Стоп",stopIntent);

        return builder.build();
    }

    private void updateNotification(String text){
        try{
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID,buildNotification(text));
        }catch(Exception ignored){}
    }

    private void scheduleRestart(){
        if(!prefs.getBoolean("scanning",false)) return;

        Intent intent=new Intent(this,RestartReceiver.class)
            .setAction("com.yt3hunter.app.RESTART_SCAN");

        PendingIntent pi=PendingIntent.getBroadcast(
            this,
            44,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);

        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime()+5000,
            pi
        );
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy(){
        releaseWake();

        if(prefs.getBoolean("scanning",false)){
            scheduleRestart();
        }

        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){
        return null;
    }
}
