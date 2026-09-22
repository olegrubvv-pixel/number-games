package com.yt3hunter.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.util.*;

public class MainActivity extends Activity {
    private final int BG=Color.rgb(7,9,13);
    private final int PANEL=Color.rgb(15,19,26);
    private final int TEXT=Color.rgb(246,248,251);
    private final int MUTED=Color.rgb(143,153,170);
    private final int ACCENT=Color.rgb(125,140,255);

    private TextView checked;
    private TextView taken;
    private TextView unknown;
    private TextView free;
    private TextView total;
    private TextView current;
    private TextView status;
    private TextView speed;
    private TextView sources;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private Db db;
    private SharedPreferences prefs;

    private int lastChecked=0;
    private long lastTick=0;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        db=new Db(this);
        prefs=getSharedPreferences(ScanService.PREFS,MODE_PRIVATE);

        setContentView(buildUi());

        if(
            Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED
        ){
            requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                7
            );
        }

        handler.post(refresh);
    }

    private View buildUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root=column();
        root.setPadding(dp(16),dp(18),dp(16),dp(40));
        scroll.addView(root);

        root.addView(text("YT3 Hunter",26,TEXT,true));
        root.addView(text(
            "Полный поиск 3-знаковых YouTube handles • нативный фоновый сервис",
            12,
            MUTED,
            false
        ));

        root.addView(space(14));

        LinearLayout stats=column();
        stats.setBackgroundColor(PANEL);
        stats.setPadding(dp(14),dp(14),dp(14),dp(14));

        root.addView(
            stats,
            new LinearLayout.LayoutParams(-1,-2)
        );

        LinearLayout row1=row();
        LinearLayout row2=row();

        checked=stat(row1,"ПРОВЕРЕНО");
        taken=stat(row1,"ЗАНЯТО");
        unknown=stat(row2,"СОМНИТЕЛЬНО");
        free=stat(row2,"СВОБОДНО");

        stats.addView(row1);
        stats.addView(space(8));
        stats.addView(row2);
        stats.addView(space(10));

        total=text("Всего в очереди: —",12,MUTED,false);
        speed=text("Скорость: 0.00/с",12,MUTED,false);
        sources=text("Источники: ещё не проверены",12,MUTED,false);
        current=text("Сейчас: —",22,TEXT,true);
        status=text("Готов",12,Color.rgb(145,223,181),false);

        stats.addView(total);
        stats.addView(speed);
        stats.addView(sources);
        stats.addView(space(5));
        stats.addView(current);
        stats.addView(space(4));
        stats.addView(status);

        root.addView(space(14));

        LinearLayout buttons=row();

        Button start=button(
            "СТАРТ / ПРОДОЛЖИТЬ",
            ACCENT
        );

        Button pause=button(
            "ПАУЗА",
            Color.rgb(23,29,39)
        );

        Button stop=button(
            "СТОП",
            Color.rgb(60,25,34)
        );

        buttons.addView(
            start,
            new LinearLayout.LayoutParams(
                0,
                dp(50),
                1.5f
            )
        );

        buttons.addView(
            pause,
            new LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        );

        buttons.addView(
            stop,
            new LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        );

        root.addView(
            buttons,
            new LinearLayout.LayoutParams(
                -1,
                -2
            )
        );

        root.addView(space(8));

        Button battery=button(
            "РАЗРЕШИТЬ РАБОТУ БЕЗ ОГРАНИЧЕНИЙ БАТАРЕИ",
            Color.rgb(23,29,39)
        );

        root.addView(
            battery,
            new LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        );

        root.addView(space(8));

        Button reset=button(
            "СБРОСИТЬ И НАЧАТЬ ПОЛНЫЙ ПОИСК ЗАНОВО",
            Color.rgb(38,20,26)
        );

        root.addView(
            reset,
            new LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        );

        root.addView(space(16));

        TextView foundTitle=text(
            "Свободные кандидаты — нажми для просмотра",
            16,
            TEXT,
            true
        );

        foundTitle.setPadding(
            0,
            dp(12),
            0,
            dp(12)
        );

        root.addView(foundTitle);

        TextView unknownTitle=text(
            "Сомнительные — нажми для просмотра причин",
            16,
            TEXT,
            true
        );

        unknownTitle.setPadding(
            0,
            dp(12),
            0,
            dp(12)
        );

        root.addView(unknownTitle);

        TextView note=text(
            "После первого полного прохода приложение автоматически перепроверяет все сомнительные до тех пор, пока они не станут занятыми или свободными. Если Android вручную сделать «Force stop» в настройках, система не позволит приложению перезапуститься до ручного открытия.",
            12,
            MUTED,
            false
        );

        note.setPadding(
            0,
            dp(12),
            0,
            0
        );

        root.addView(note);

        start.setOnClickListener(
            v->send(
                ScanService.ACTION_START
            )
        );

        pause.setOnClickListener(
            v->send(
                prefs.getBoolean(
                    "paused",
                    false
                )
                    ? ScanService.ACTION_RESUME
                    : ScanService.ACTION_PAUSE
            )
        );

        stop.setOnClickListener(
            v->send(
                ScanService.ACTION_STOP
            )
        );

        battery.setOnClickListener(
            v->requestBatteryExemption()
        );

        foundTitle.setOnClickListener(
            v->showRows(
                Db.FREE,
                "Свободные"
            )
        );

        unknownTitle.setOnClickListener(
            v->showRows(
                Db.UNKNOWN,
                "Сомнительные"
            )
        );

        unknown.setOnClickListener(
            v->showRows(
                Db.UNKNOWN,
                "Сомнительные"
            )
        );

        free.setOnClickListener(
            v->showRows(
                Db.FREE,
                "Свободные"
            )
        );

        reset.setOnClickListener(
            v->
                new AlertDialog.Builder(this)
                    .setTitle("Сбросить всё?")
                    .setMessage(
                        "Прогресс и найденные результаты будут удалены."
                    )
                    .setNegativeButton(
                        "Отмена",
                        null
                    )
                    .setPositiveButton(
                        "Сбросить",
                        (dialog,which)->
                            resetAll()
                    )
                    .show()
        );

        return scroll;
    }

    private void send(String action){
        Intent intent=
            new Intent(
                this,
                ScanService.class
            ).setAction(action);

        try{
            if(Build.VERSION.SDK_INT>=26){
                startForegroundService(intent);
            }else{
                startService(intent);
            }

        }catch(Exception e){
            Toast.makeText(
                this,
                e.toString(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void requestBatteryExemption(){
        if(Build.VERSION.SDK_INT<23){
            return;
        }

        try{
            Intent intent=
                new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse(
                        "package:"+
                        getPackageName()
                    )
                );

            startActivity(intent);

        }catch(Exception e){
            startActivity(
                new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                )
            );
        }
    }

    private void resetAll(){
        send(ScanService.ACTION_STOP);

        db.clearAll();
        prefs.edit().clear().apply();

        refresh.run();

        Toast.makeText(
            this,
            "Сброшено",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void showRows(
        int type,
        String title
    ){
        new Thread(()->{
            List<Db.Row> rows=
                db.rows(
                    type,
                    1000
                );

            ArrayList<String> display=
                new ArrayList<>();

            for(Db.Row row:rows){
                String reason=
                    row.reason==null
                        ? ""
                        : row.reason;

                display.add(
                    "@"+
                    row.handle+
                    "\n"+
                    reason+
                    (
                        type==Db.UNKNOWN
                            ? "\nпопыток: "+
                              row.attempts
                            : ""
                    )
                );
            }

            runOnUiThread(()->{
                if(display.isEmpty()){
                    Toast.makeText(
                        this,
                        "Пока пусто",
                        Toast.LENGTH_SHORT
                    ).show();

                    return;
                }

                AlertDialog dialog=
                    new AlertDialog.Builder(this)
                        .setTitle(
                            title+
                            " ("+
                            display.size()+
                            (
                                rows.size()>=1000
                                    ? "+"
                                    : ""
                            )+
                            ")"
                        )
                        .setItems(
                            display.toArray(
                                new String[0]
                            ),
                            (d,which)->{
                                String handle=
                                    rows.get(which)
                                        .handle;

                                Intent open=
                                    new Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "https://www.youtube.com/@"+
                                            handle
                                        )
                                    );

                                startActivity(open);
                            }
                        )
                        .setNegativeButton(
                            "Закрыть",
                            null
                        )
                        .create();

                dialog.show();

                ListView list=
                    dialog.getListView();

                list.setBackgroundColor(PANEL);
                list.setDividerHeight(1);
            });

        }).start();
    }

    private final Runnable refresh=
        new Runnable(){
            @Override public void run(){
                int c=
                    prefs.getInt(
                        "checked",
                        0
                    );

                int t=
                    prefs.getInt(
                        "taken",
                        0
                    );

                int all=
                    prefs.getInt(
                        "total",
                        0
                    );

                checked.setText(
                    String.valueOf(c)
                );

                taken.setText(
                    String.valueOf(t)
                );

                int u=
                    db.count(
                        Db.UNKNOWN
                    );

                int f=
                    db.count(
                        Db.FREE
                    );

                unknown.setText(
                    String.valueOf(u)
                );

                free.setText(
                    String.valueOf(f)
                );

                total.setText(
                    "Всего в очереди: "+
                    all+
                    " • осталось: "+
                    Math.max(
                        0,
                        all-c
                    )
                );

                int sourceCount=
                    prefs.getInt(
                        "source_count",
                        0
                    );

                String sourceNames=
                    prefs.getString(
                        "sources",
                        ""
                    );

                sources.setText(
                    sourceCount==0
                        ? "Источники: проверяются…"
                        : "Источники: "+
                          sourceCount+
                          " • "+
                          sourceNames
                );

                String nowHandle=
                    prefs.getString(
                        "current",
                        ""
                    );

                current.setText(
                    nowHandle.isEmpty()
                        ? "Сейчас: —"
                        : "Сейчас: @"+
                          nowHandle
                );

                boolean running=
                    prefs.getBoolean(
                        "scanning",
                        false
                    );

                boolean isPaused=
                    prefs.getBoolean(
                        "paused",
                        false
                    );

                String phase=
                    prefs.getString(
                        "phase",
                        "initial"
                    );

                if(running){
                    if(isPaused){
                        status.setText(
                            "Пауза"
                        );

                    }else if(
                        "health".equals(
                            phase
                        )
                    ){
                        status.setText(
                            "Проверяю источники…"
                        );

                    }else if(
                        "waiting_sources".equals(
                            phase
                        )
                    ){
                        status.setText(
                            "Жду минимум 2 рабочих источника"
                        );

                    }else if(
                        "retry".equals(
                            phase
                        )
                    ){
                        status.setText(
                            "Перепроверка сомнительных"
                        );

                    }else{
                        status.setText(
                            "Полное сканирование"
                        );
                    }

                }else{
                    status.setText(
                        "done".equals(
                            phase
                        )
                            ? "Готово полностью"
                            : "Остановлено"
                    );
                }

                long now=
                    System.currentTimeMillis();

                if(lastTick>0){
                    double perSecond=
                        (c-lastChecked)/
                        Math.max(
                            .001,
                            (now-lastTick)/1000.0
                        );

                    speed.setText(
                        String.format(
                            Locale.US,
                            "Скорость: %.2f юз/с",
                            Math.max(
                                0,
                                perSecond
                            )
                        )
                    );
                }

                lastChecked=c;
                lastTick=now;

                handler.postDelayed(
                    this,
                    1000
                );
            }
        };

    private TextView stat(
        LinearLayout parent,
        String label
    ){
        LinearLayout box=column();

        box.setPadding(
            dp(12),
            dp(11),
            dp(12),
            dp(11)
        );

        box.setBackgroundColor(
            Color.rgb(
                9,
                13,
                19
            )
        );

        box.addView(
            text(
                label,
                10,
                MUTED,
                false
            )
        );

        TextView value=
            text(
                "0",
                24,
                TEXT,
                true
            );

        box.addView(value);

        LinearLayout.LayoutParams params=
            new LinearLayout.LayoutParams(
                0,
                dp(82),
                1f
            );

        params.setMargins(
            dp(4),
            0,
            dp(4),
            0
        );

        parent.addView(
            box,
            params
        );

        return value;
    }

    private Button button(
        String label,
        int color
    ){
        Button button=
            new Button(this);

        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(11);
        button.setBackgroundColor(color);
        button.setAllCaps(false);

        return button;
    }

    private TextView text(
        String value,
        int size,
        int color,
        boolean bold
    ){
        TextView view=
            new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if(bold){
            view.setTypeface(
                null,
                1
            );
        }

        view.setLineSpacing(
            0,
            1.08f
        );

        return view;
    }

    private LinearLayout column(){
        LinearLayout layout=
            new LinearLayout(this);

        layout.setOrientation(
            LinearLayout.VERTICAL
        );

        return layout;
    }

    private LinearLayout row(){
        LinearLayout layout=
            new LinearLayout(this);

        layout.setOrientation(
            LinearLayout.HORIZONTAL
        );

        return layout;
    }

    private View space(int height){
        Space space=
            new Space(this);

        space.setLayoutParams(
            new LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        );

        return space;
    }

    private int dp(int value){
        return (int)(
            value*
            getResources()
                .getDisplayMetrics()
                .density+
            .5f
        );
    }

    @Override protected void onDestroy(){
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }
}
