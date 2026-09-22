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

    private static final String[][] PROVIDERS={
        {"YouTube","youtube","https://www.youtube.com/@"},
        {"F5","f5","https://invidious.f5.si/api/v1/resolveurl?url="},
        {"Nadeko","nadeko","https://inv.nadeko.net/api/v1/resolveurl?url="},
        {"Tiekoetter","tie","https://invidious.tiekoetter.com/api/v1/resolveurl?url="}
    };

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
    private AtomicInteger processedCount=new AtomicInteger();
    private AtomicInteger takenCount=new AtomicInteger();
    private final AtomicInteger notificationTick=new AtomicInteger();

    private final ExecutorService netPool=Executors.newFixedThreadPool(8);
    private volatile List<Integer> activeProviders=Collections.emptyList();
    private volatile long lastHealthCheck=0L;

    private static final ConcurrentHashMap<String,Gate> GATES=new ConcurrentHashMap<>();

    static class Gate {
        long lastStart=0;
        long backoffUntil=0;
        int fail=0;

        synchronized void before() throws InterruptedException {
            while(true){
                long now=System.currentTimeMillis();
                long wait=Math.max(backoffUntil-now,70-(now-lastStart));
                if(wait<=0){
                    lastStart=now;
                    return;
                }
                wait(wait);
            }
        }

        synchronized void ok(){
            fail=Math.max(0,fail-1);
            if(fail==0) backoffUntil=0;
            notifyAll();
        }

        synchronized void failed(boolean hard){
            fail=Math.min(8,fail+(hard?2:1));
            long ms=Math.min(hard?30000:12000,(long)(700*Math.pow(1.6,fail)));
            backoffUntil=Math.max(backoffUntil,System.currentTimeMillis()+ms);
            notifyAll();
        }

        synchronized boolean cooling(){
            return backoffUntil>System.currentTimeMillis();
        }
    }

    static class Verdict {
        static final int TAKEN=1;
        static final int FREE=2;
        static final int UNKNOWN=3;

        final int type;
        final String reason;

        Verdict(int type,String reason){
            this.type=type;
            this.reason=reason;
        }
    }

    static class ProviderVerdict {
        final int providerIndex;
        final Verdict verdict;

        ProviderVerdict(int providerIndex,Verdict verdict){
            this.providerIndex=providerIndex;
            this.verdict=verdict;
        }
    }

    @Override public void onCreate(){
        super.onCreate();

        db=new Db(this);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        createChannel();

        for(String[] provider:PROVIDERS){
            GATES.putIfAbsent(provider[1],new Gate());
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

        prefs.edit()
            .putBoolean("scanning",true)
            .apply();

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

                prefs.edit().putString("phase","health").apply();
                refreshProviders(true);

                if(activeProviders.size()<2){
                    prefs.edit().putString("phase","waiting_sources").apply();
                    updateNotification("Жду минимум 2 рабочих источника");

                    while(scanning && activeProviders.size()<2){
                        waitIfPaused();
                        Thread.sleep(5000);
                        refreshProviders(true);
                    }
                }

                if(!scanning) return;

                if("retry".equals(phase)){
                    prefs.edit().putString("phase","retry").apply();
                    runRetryLoop();
                }else{
                    prefs.edit().putString("phase","initial").apply();
                    runInitial();

                    if(scanning){
                        runRetryLoop();
                    }
                }

                return;

            }catch(Throwable error){
                prefs.edit()
                    .putString("last_error",String.valueOf(error))
                    .apply();

                updateNotification("Ошибка — повтор через 10 сек");

                try{
                    Thread.sleep(10000);
                }catch(InterruptedException ignored){
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private synchronized void refreshProviders(boolean force){
        long now=System.currentTimeMillis();

        if(!force && now-lastHealthCheck<30000) return;

        lastHealthCheck=now;

        ArrayList<Future<ProviderVerdict>> futures=new ArrayList<>();

        for(int i=0;i<PROVIDERS.length;i++){
            final int index=i;
            futures.add(netPool.submit(
                ()->new ProviderVerdict(index,checkProvider(index,"youtube"))
            ));
        }

        ArrayList<Integer> good=new ArrayList<>();
        ArrayList<String> names=new ArrayList<>();

        for(Future<ProviderVerdict> future:futures){
            try{
                ProviderVerdict result=future.get(6,TimeUnit.SECONDS);

                if(result.verdict.type==Verdict.TAKEN){
                    good.add(result.providerIndex);
                    names.add(PROVIDERS[result.providerIndex][0]);
                }
            }catch(Exception ignored){
                future.cancel(true);
            }
        }

        activeProviders=Collections.unmodifiableList(good);

        prefs.edit()
            .putInt("source_count",good.size())
            .putString("sources",String.join(", ",names))
            .apply();

        updateNotification(
            good.size()>=2
                ? "Источники: "+good.size()+" • "+String.join(", ",names)
                : "Рабочих источников: "+good.size()+"/2"
        );
    }

    private List<Integer> providerSnapshot(){
        if(System.currentTimeMillis()-lastHealthCheck>30000){
            refreshProviders(false);
        }

        ArrayList<Integer> snapshot=new ArrayList<>();

        for(int index:activeProviders){
            Gate gate=GATES.get(PROVIDERS[index][1]);

            if(gate!=null && !gate.cooling()){
                snapshot.add(index);
            }
        }

        if(snapshot.size()<2){
            refreshProviders(true);
            snapshot.clear();

            for(int index:activeProviders){
                Gate gate=GATES.get(PROVIDERS[index][1]);

                if(gate!=null && !gate.cooling()){
                    snapshot.add(index);
                }
            }
        }

        return snapshot;
    }

    private void runInitial() throws InterruptedException {
        safeCursor=Math.min(prefs.getInt("cursor",0),handles.size());
        nextIndex=new AtomicInteger(safeCursor);
        processedCount=new AtomicInteger(safeCursor);
        takenCount.set(prefs.getInt("taken",0));
        completed.clear();

        int workers=4;

        ExecutorService workersPool=Executors.newFixedThreadPool(workers);
        CountDownLatch latch=new CountDownLatch(workers);

        for(int w=0;w<workers;w++){
            workersPool.execute(()->{
                try{
                    while(scanning){
                        waitIfPaused();

                        int index=nextIndex.getAndIncrement();

                        if(index>=handles.size()) break;

                        String handle=handles.get(index);

                        prefs.edit()
                            .putString("current",handle)
                            .apply();

                        Verdict verdict=verifyStrict(handle);

                        applyInitial(handle,verdict);
                        markCompleted(index);
                    }

                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();

                }finally{
                    latch.countDown();
                }
            });
        }

        latch.await();
        workersPool.shutdownNow();

        if(!scanning) return;

        prefs.edit()
            .putInt("cursor",handles.size())
            .putInt("checked",handles.size())
            .putString("phase","retry")
            .apply();

        updateNotification("Первый проход завершён");
    }

    private void markCompleted(int index){
        int processed=processedCount.incrementAndGet();

        synchronized(cursorLock){
            completed.add(index);

            while(completed.remove(safeCursor)){
                safeCursor++;
            }

            prefs.edit()
                .putInt("cursor",safeCursor)
                .putInt("checked",processed)
                .apply();
        }

        if(notificationTick.incrementAndGet()%10==0){
            updateNotification(null);
        }
    }

    private void applyInitial(String handle,Verdict verdict){
        if(verdict.type==Verdict.TAKEN){
            takenCount.incrementAndGet();
            db.remove(handle);

            prefs.edit()
                .putInt("taken",takenCount.get())
                .apply();

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

            refreshProviders(false);

            if(activeProviders.size()<2){
                prefs.edit().putString("phase","waiting_sources").apply();
                updateNotification("Перепроверка ждёт 2 источника");

                Thread.sleep(5000);
                refreshProviders(true);

                if(activeProviders.size()>=2){
                    prefs.edit().putString("phase","retry").apply();
                }

                continue;
            }

            List<String> pending=db.unknownHandles();

            if(pending.isEmpty()){
                completeAll();
                return;
            }

            updateNotification("Перепроверка сомнительных: "+pending.size());

            for(String handle:pending){
                if(!scanning) return;

                waitIfPaused();

                prefs.edit()
                    .putString("current",handle)
                    .apply();

                Verdict verdict=verifyStrict(handle);

                if(verdict.type==Verdict.TAKEN){
                    db.remove(handle);
                    takenCount.incrementAndGet();

                    prefs.edit()
                        .putInt("taken",takenCount.get())
                        .apply();

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
                Thread.sleep(15000);
            }
        }
    }

    private Verdict verifyStrict(String handle){
        Verdict first=verifyPass(handle);

        if(first.type!=Verdict.FREE){
            return first;
        }

        try{
            Thread.sleep(500+new Random().nextInt(250));

        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return new Verdict(Verdict.UNKNOWN,"interrupted");
        }

        Verdict second=verifyPass(handle);

        if(second.type==Verdict.FREE){
            return new Verdict(
                Verdict.FREE,
                first.reason+" | repeat ok"
            );
        }

        if(second.type==Verdict.TAKEN){
            return second;
        }

        return new Verdict(
            Verdict.UNKNOWN,
            "repeat: "+second.reason
        );
    }

    private Verdict verifyPass(String handle){
        List<Integer> providers=providerSnapshot();

        if(providers.size()<2){
            return new Verdict(
                Verdict.UNKNOWN,
                "less than 2 working sources"
            );
        }

        ArrayList<Future<ProviderVerdict>> futures=new ArrayList<>();

        for(int providerIndex:providers){
            futures.add(netPool.submit(
                ()->new ProviderVerdict(
                    providerIndex,
                    checkProvider(providerIndex,handle)
                )
            ));
        }

        ArrayList<String> freeBy=new ArrayList<>();
        ArrayList<String> unknownBy=new ArrayList<>();

        for(Future<ProviderVerdict> future:futures){
            try{
                ProviderVerdict result=future.get(6,TimeUnit.SECONDS);
                String name=PROVIDERS[result.providerIndex][0];

                if(result.verdict.type==Verdict.TAKEN){
                    for(Future<ProviderVerdict> other:futures){
                        if(other!=future) other.cancel(true);
                    }

                    return result.verdict;
                }

                if(result.verdict.type==Verdict.FREE){
                    freeBy.add(name);
                }else{
                    unknownBy.add(
                        name+":"+result.verdict.reason
                    );
                }

            }catch(Exception error){
                future.cancel(true);
                unknownBy.add("timeout");
            }
        }

        if(freeBy.size()>=2){
            return new Verdict(
                Verdict.FREE,
                "free: "+String.join(",",freeBy)
            );
        }

        return new Verdict(
            Verdict.UNKNOWN,
            "need 2 confirmations; "+String.join(" | ",unknownBy)
        );
    }

    private Verdict checkProvider(int providerIndex,String handle){
        if(providerIndex==0){
            return checkYouTube(handle);
        }

        return checkInvidious(
            PROVIDERS[providerIndex][1],
            PROVIDERS[providerIndex][2],
            handle
        );
    }

    private Verdict checkYouTube(String handle){
        Gate gate=GATES.get("youtube");

        try{
            gate.before();

            String encoded=URLEncoder.encode(handle,"UTF-8");

            URL url=new URL(
                PROVIDERS[0][2]+
                encoded+
                "?hl=en"
            );

            HttpURLConnection connection=(HttpURLConnection)url.openConnection();

            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
            );

            int code=connection.getResponseCode();
            String text=read(connection,code);
            String lower=text.toLowerCase(Locale.ROOT);

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
                return new Verdict(
                    Verdict.FREE,
                    "YouTube "+code
                );
            }

            if(
                lower.contains("\"channelid\":\"uc") ||
                lower.contains("\"externalid\":\"uc") ||
                lower.contains("\"browseid\":\"uc") ||
                lower.contains("youtube.com/channel/uc") ||
                lower.contains("\"canonicalbaseurl\":\"/@")
            ){
                gate.ok();

                return new Verdict(
                    Verdict.TAKEN,
                    "YouTube channel metadata"
                );
            }

            if(
                lower.contains("this page isn't available") ||
                lower.contains("this page isn’t available") ||
                lower.contains("this channel does not exist")
            ){
                gate.ok();

                return new Verdict(
                    Verdict.FREE,
                    "YouTube not found page"
                );
            }

            gate.ok();

            return new Verdict(
                Verdict.UNKNOWN,
                "ambiguous "+code
            );

        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return new Verdict(Verdict.UNKNOWN,"interrupted");

        }catch(Exception e){
            gate.failed(false);

            return new Verdict(
                Verdict.UNKNOWN,
                e.getClass().getSimpleName()
            );
        }
    }

    private Verdict checkInvidious(
        String key,
        String base,
        String handle
    ){
        Gate gate=GATES.get(key);

        try{
            gate.before();

            String target=
                "https://www.youtube.com/@"+
                handle;

            String encoded=
                URLEncoder.encode(
                    target,
                    "UTF-8"
                );

            URL url=new URL(base+encoded);

            HttpURLConnection connection=
                (HttpURLConnection)url.openConnection();

            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setRequestProperty(
                "Accept",
                "application/json"
            );

            int code=connection.getResponseCode();
            String text=read(connection,code);
            String lower=text.toLowerCase(Locale.ROOT);

            String compact=lower
                .replace(" ","")
                .replace("\n","")
                .replace("\t","");

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

                return new Verdict(
                    Verdict.TAKEN,
                    key+" resolved channel"
                );
            }

            if(
                code==404 ||
                (
                    code==400 &&
                    (
                        lower.contains("not found") ||
                        lower.contains("invalid") ||
                        lower.contains("could not resolve") ||
                        lower.contains("unable to resolve") ||
                        lower.contains("does not exist") ||
                        lower.contains("no matching")
                    )
                )
            ){
                gate.ok();

                return new Verdict(
                    Verdict.FREE,
                    key+" unresolved"
                );
            }

            gate.ok();

            return new Verdict(
                Verdict.UNKNOWN,
                "http "+code
            );

        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return new Verdict(Verdict.UNKNOWN,"interrupted");

        }catch(Exception e){
            gate.failed(false);

            return new Verdict(
                Verdict.UNKNOWN,
                e.getClass().getSimpleName()
            );
        }
    }

    private String read(
        HttpURLConnection connection,
        int code
    ) throws IOException {
        InputStream input=
            code>=400
                ? connection.getErrorStream()
                : connection.getInputStream();

        if(input==null) return "";

        ByteArrayOutputStream output=
            new ByteArrayOutputStream();

        byte[] buffer=new byte[8192];
        int n;
        int total=0;

        while(
            (n=input.read(buffer))>0 &&
            total<1200000
        ){
            output.write(buffer,0,n);
            total+=n;
        }

        input.close();

        return output.toString(
            StandardCharsets.UTF_8.name()
        );
    }

    private void waitIfPaused() throws InterruptedException {
        while(scanning && paused){
            Thread.sleep(300);
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

        NotificationManager manager=
            (NotificationManager)
                getSystemService(
                    NOTIFICATION_SERVICE
                );

        manager.notify(
            NOTIF_ID,
            buildNotification(
                "Готово: вся очередь проверена, сомнительных 0"
            )
        );

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
        if(
            wakeLock!=null &&
            wakeLock.isHeld()
        ){
            return;
        }

        PowerManager manager=
            (PowerManager)
                getSystemService(
                    POWER_SERVICE
                );

        wakeLock=manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YT3Hunter:Scan"
        );

        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWake(){
        try{
            if(
                wakeLock!=null &&
                wakeLock.isHeld()
            ){
                wakeLock.release();
            }
        }catch(Exception ignored){}
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=
                new NotificationChannel(
                    CHANNEL,
                    "YT3 background scan",
                    NotificationManager.IMPORTANCE_LOW
                );

            channel.setDescription(
                "Keeps the complete handle scan running in the background"
            );

            ((NotificationManager)
                getSystemService(
                    NOTIFICATION_SERVICE
                ))
                .createNotificationChannel(
                    channel
                );
        }
    }

    private Notification buildNotification(
        String override
    ){
        Intent open=
            new Intent(
                this,
                MainActivity.class
            );

        PendingIntent content=
            PendingIntent.getActivity(
                this,
                1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        String pauseAction=
            paused
                ? ACTION_RESUME
                : ACTION_PAUSE;

        PendingIntent pauseIntent=
            PendingIntent.getService(
                this,
                2,
                new Intent(
                    this,
                    ScanService.class
                ).setAction(pauseAction),
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        PendingIntent stopIntent=
            PendingIntent.getService(
                this,
                3,
                new Intent(
                    this,
                    ScanService.class
                ).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        int checked=
            prefs.getInt("checked",0);

        int total=
            prefs.getInt("total",0);

        int free=
            db==null
                ? 0
                : db.count(Db.FREE);

        int unknown=
            db==null
                ? 0
                : db.count(Db.UNKNOWN);

        String text=
            override!=null
                ? override
                : checked+
                    "/"+
                    total+
                    " • свободно "+
                    free+
                    " • сомнительно "+
                    unknown;

        Notification.Builder builder;

        if(Build.VERSION.SDK_INT>=26){
            builder=
                new Notification.Builder(
                    this,
                    CHANNEL
                );
        }else{
            builder=
                new Notification.Builder(this);
        }

        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(
                "YT3 Hunter — "+
                (paused?"пауза":"проверка идёт")
            )
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(scanning)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                paused
                    ? "Продолжить"
                    : "Пауза",
                pauseIntent
            )
            .addAction(
                0,
                "Стоп",
                stopIntent
            );

        return builder.build();
    }

    private void updateNotification(String text){
        try{
            ((NotificationManager)
                getSystemService(
                    NOTIFICATION_SERVICE
                ))
                .notify(
                    NOTIF_ID,
                    buildNotification(text)
                );
        }catch(Exception ignored){}
    }

    private void scheduleRestart(){
        if(!prefs.getBoolean("scanning",false)){
            return;
        }

        Intent intent=
            new Intent(
                this,
                RestartReceiver.class
            ).setAction(
                "com.yt3hunter.app.RESTART_SCAN"
            );

        PendingIntent pendingIntent=
            PendingIntent.getBroadcast(
                this,
                44,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        AlarmManager alarmManager=
            (AlarmManager)
                getSystemService(
                    ALARM_SERVICE
                );

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime()+5000,
            pendingIntent
        );
    }

    @Override public void onTaskRemoved(
        Intent rootIntent
    ){
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy(){
        releaseWake();

        if(
            prefs.getBoolean(
                "scanning",
                false
            )
        ){
            scheduleRestart();
        }

        netPool.shutdownNow();

        super.onDestroy();
    }

    @Override public IBinder onBind(
        Intent intent
    ){
        return null;
    }
}
