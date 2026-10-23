package com.example.l2sbuilder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// يستقبل منبّه المراقب الدوري وإقلاع الهاتف وتحديث التطبيق، ويعيد تشغيل خدمة الخلفية إن كان الوضع مفعّلًا،
// وينبّه إن انقطعت عملية قبل أن تنتهي.
public class BgWatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String a = intent == null ? null : intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                    || BuildService.ACTION_WATCHDOG.equals(a)) {
                BuildService.onWatchdog(ctx.getApplicationContext());
            }
        } catch (Throwable ignored) {}
    }
}
