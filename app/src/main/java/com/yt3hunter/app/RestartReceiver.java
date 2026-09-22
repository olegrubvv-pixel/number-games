package com.yt3hunter.app;

import android.content.*;
import android.os.Build;

public class RestartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean scanning=context
            .getSharedPreferences(ScanService.PREFS,Context.MODE_PRIVATE)
            .getBoolean("scanning",false);

        if(!scanning) return;

        Intent service=new Intent(context,ScanService.class)
            .setAction(ScanService.ACTION_START);

        try {
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(service);
            else context.startService(service);
        } catch(Exception ignored) {}
    }
}
