package com.example.l2sbuilder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

// خدمة أمامية (Foreground Service) تحمي عملية التطبيق من القتل — بنفس مبدأ Termux.
//
// لها وضعان يعملان معًا بعدّاد مشترك:
//  1) وضع المهمة: يُفعَّل تلقائيًا أثناء أي بناء/أمر (start/stop من startTask) ويُنهى بنهايتها.
//  2) وضع الخلفية الدائم (السويتش في الأعلى): تبقى الخدمة تعمل حتى يطفئه المستخدم،
//     مع WakeLock جزئي + WifiLock فتستمر كل العمليات حتى مع إطفاء الشاشة.
//     يُحفظ الاختيار ويُستعاد عند فتح التطبيق، والخدمة START_STICKY فيعيدها النظام إن قُتلت.
//
// كل الأوامر تعمل كـ Threads/عمليات فرعية داخل نفس عملية التطبيق، ووجود خدمة أمامية
// نشطة يرفع أولوية العملية كاملة فلا يقتلها النظام عند الحاجة للذاكرة.
public class BuildService extends Service {

    // قناة جديدة بأهمية MIN: إشعار صامت بلا أيقونة في شريط الحالة ولا ظهور فوق الشاشة.
    // (أهمية القناة القديمة لا يمكن تغييرها بعد إنشائها، لذلك تُحذف القديمة وتُستبدل.)
    private static final String CHANNEL_ID = "build_running_channel_v3";
    private static final String OLD_CHANNEL_ID = "build_running_channel_v2";
    private static final String OLD_CHANNEL_ID0 = "build_running_channel";
    private static final int NOTIF_ID = 9001;
    private static final String PREFS = "l2s_ui";
    private static final String KEY_BG = "bgmode";
    private static final String ACTION_EXIT = "com.example.l2sbuilder.EXIT_BG";

    private static final Object LOCK = new Object();
    private static int tasks = 0;

    // ===== تشديد البقاء في الخلفية =====
    // 1) سجل العملية الجارية (rt_*): يُكتب بشكل متزامن عند بدء بناء/أمر ويُمسح عند انتهائه الطبيعي؛
    //    إن بقي بعد قتل التطبيق أو إعادة تشغيل الهاتف نعرف أن عملية انقطعت ونعرض خيار الاستئناف.
    // 2) مراقب (AlarmManager + BgWatchdogReceiver): كل 5 دقائق، وعند سحب التطبيق من الأخيرة،
    //    وبعد إعادة تشغيل الهاتف، يعيد تشغيل الخدمة إن كان وضع الخلفية مفعّلًا.
    public static final String ACTION_WATCHDOG = "com.example.l2sbuilder.WATCHDOG";
    private static final long WATCHDOG_MS = 5 * 60_000L;
    private static final int INTERRUPTED_NOTIF_ID = 9003;

    // إيقاف وضع الخلفية تلقائيًا عند الخمول: إن لم تكن هناك عملية جارية لمدة idle_stop_min دقيقة
    // (والوضع الدائم مفعّل) يُطفأ الوضع وتتوقف الخدمة ويُحرَّر قفل النوم. 0 = معطّل.
    public static final int DEFAULT_IDLE_MIN = 30;
    private static final String KEY_IDLE_MIN = "idle_stop_min";
    private static final String AUTOSTOP_CHANNEL_ID = "autostop_channel";
    private static final android.os.Handler IDLE_H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable idleTask = null;
    private static volatile BuildService inst = null;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // ------------------------------------------------------------------ واجهة ثابتة

    public static boolean isPersistent(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BG, false);
    }

    /** السويتش: تشغيل/إيقاف وضع الخلفية الدائم. */
    public static void setPersistent(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_BG, on).apply();
        synchronized (LOCK) {
            if (on) {
                launch(ctx);
                BuildService s = inst;
                if (s != null) s.refresh();
            } else if (tasks <= 0) {
                halt(ctx);
            } else {
                BuildService s = inst;
                if (s != null) s.refresh();
            }
            scheduleIdleLocked(ctx.getApplicationContext());
        }
        syncWatchdog(ctx);
    }

    // ---------------------------------------------------------------- سجل العملية الجارية

    private static volatile String statusText = null;

    /** نص حالة يظهر في الإشعار أثناء المهمة (null لمسحه). */
    public static void setStatus(Context ctx, String t) {
        statusText = t;
        BuildService s = inst;
        if (s != null) s.refresh();
    }

    public static void noteTask(Context ctx, String kind, String name, String text) {
        try {
            prefs(ctx).edit()
                    .putString("rt_kind", kind == null ? "" : kind)
                    .putString("rt_name", name == null ? "" : name)
                    .putString("rt_text", text == null ? "" : text)
                    .putLong("rt_ts", System.currentTimeMillis())
                    .remove("rt_notified")
                    .commit();
        } catch (Throwable ignored) {}
        syncWatchdog(ctx);
    }

    public static void clearTaskNote(Context ctx) {
        try {
            prefs(ctx).edit().remove("rt_kind").remove("rt_name").remove("rt_text").remove("rt_ts").remove("rt_notified").commit();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(INTERRUPTED_NOTIF_ID);
        } catch (Throwable ignored) {}
        syncWatchdog(ctx);
    }

    // ---------------------------------------------------------------- استئناف تلقائي في الخلفية
    // عند عودة العملية بعد قتلها (إعادة تشغيل الخدمة START_STICKY أو منبّه المراقب أو إقلاع الهاتف)
    // نعيد تشغيل العملية المنقطعة دون أن ينتظر المستخدم دخول التطبيق:
    //  - نحاول فتح MainActivity مباشرة (يعمل إن مُنح إذن «الظهور فوق التطبيقات» أو سمح النظام)،
    //    وهي تبدأ العملية فورًا ثم تنتقل للخلفية؛
    //  - ونرسل إشعارًا عالي الأهمية بنية ملء الشاشة كمسار احتياطي.
    // حماية: لا أكثر من 5 محاولات خلال 10 دقائق حتى لا تدور الحلقة إن كان سبب القتل مستمرًا.
    public static boolean consumeAutoResume(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        long now = System.currentTimeMillis();
        int n = sp.getInt("auto_resume_n", 0);
        long first = sp.getLong("auto_resume_ts", 0);
        if (now - first > 10 * 60_000L) { n = 0; first = now; }
        n++;
        if (n > 5) return false;
        sp.edit().putInt("auto_resume_n", n).putLong("auto_resume_ts", first == 0 ? now : first).commit();
        return true;
    }

    static boolean tryAutoResume(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (!hasInterrupted(app)) return false;
            SharedPreferences sp = prefs(app);
            if (!sp.getBoolean("auto_resume_bg", true)) return false;
            if (!consumeAutoResume(app)) return false;
            Intent i = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("auto_resume", true);
            try { app.startActivity(i); } catch (Throwable ignored) {}
            try {
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("autoresume_channel") == null) {
                        nm.createNotificationChannel(new NotificationChannel(
                                "autoresume_channel", "استئناف تلقائي للعملية المنقطعة", NotificationManager.IMPORTANCE_HIGH));
                    }
                    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                    PendingIntent pi = PendingIntent.getActivity(app, 3, i, fl);
                    Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                            ? new Notification.Builder(app, "autoresume_channel") : new Notification.Builder(app);
                    b.setContentTitle("استئناف العملية المنقطعة")
                            .setContentText("يجري استئناف العملية تلقائيًا — اضغط هنا إن لم تبدأ")
                            .setSmallIcon(android.R.drawable.stat_sys_warning)
                            .setContentIntent(pi)
                            .setFullScreenIntent(pi, true)
                            .setAutoCancel(true);
                    nm.notify(INTERRUPTED_NOTIF_ID, b.build());
                }
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable e) { return false; }
    }

    /** true إن بقي سجل عملية بلا مهمة جارية في هذه العملية = انقطعت قبل أن تنتهي. */
    public static boolean hasInterrupted(Context ctx) {
        synchronized (LOCK) {
            return tasks <= 0 && !prefs(ctx).getString("rt_kind", "").isEmpty();
        }
    }

    private static void notifyInterrupted(Context app) {
        try {
            SharedPreferences sp = prefs(app);
            String kind = sp.getString("rt_kind", "");
            if (kind.isEmpty() || sp.getBoolean("rt_notified", false)) return;
            if (Build.VERSION.SDK_INT >= 33
                    && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(AUTOSTOP_CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        AUTOSTOP_CHANNEL_ID, "إيقاف تلقائي للبناء/الأوامر", NotificationManager.IMPORTANCE_HIGH));
            }
            String name = sp.getString("rt_name", "");
            String what = "build".equals(kind) ? "بناء المشروع" : "أمر في المشروع";
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent open = PendingIntent.getActivity(app, 2,
                    new Intent(app, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(app, AUTOSTOP_CHANNEL_ID) : new Notification.Builder(app);
            b.setContentTitle("انقطعت عملية قبل أن تنتهي")
                    .setContentText(what + " «" + name + "» — افتح التطبيق للاستئناف")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentIntent(open)
                    .setAutoCancel(true);
            nm.notify(INTERRUPTED_NOTIF_ID, b.build());
            sp.edit().putBoolean("rt_notified", true).apply();
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- المراقب

    private static PendingIntent watchdogPi(Context app) {
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(app, 7, new Intent(app, BgWatchdogReceiver.class).setAction(ACTION_WATCHDOG), fl);
    }

    private static void armWatchdog(Context app, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + delayMs;
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogPi(app));
            else am.set(AlarmManager.RTC_WAKEUP, at, watchdogPi(app));
        } catch (Throwable ignored) {}
    }

    private static void disarmWatchdog(Context app) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(watchdogPi(app));
        } catch (Throwable ignored) {}
    }

    /** يضبط المراقب حسب حالة وضع الخلفية. */
    static void syncWatchdog(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean rec = !prefs(app).getString("rt_kind", "").isEmpty();
        // أثناء عملية مسجّلة: فحص كل دقيقة ليعود الاستئناف سريعًا إن قُتلت العملية.
        if (rec) armWatchdog(app, 60_000L);
        else if (isPersistent(app)) armWatchdog(app, WATCHDOG_MS);
        else disarmWatchdog(app);
    }

    /** يُستدعى من BgWatchdogReceiver (منبّه دوري / إقلاع الهاتف / تحديث التطبيق). */
    static void onWatchdog(Context app) {
        boolean rec = !prefs(app).getString("rt_kind", "").isEmpty();
        if (isPersistent(app) || rec) launch(app);
        syncWatchdog(app);
        if (hasInterrupted(app) && !tryAutoResume(app)) notifyInterrupted(app);
    }

    /** يُستدعى عند فتح التطبيق لاستعادة الوضع المحفوظ. */
    public static void restoreIfEnabled(Context ctx) {
        if (isPersistent(ctx)) launch(ctx);
        synchronized (LOCK) { scheduleIdleLocked(ctx.getApplicationContext()); }
    }

    public static int idleMinutes(Context ctx) {
        int v = prefs(ctx).getInt(KEY_IDLE_MIN, DEFAULT_IDLE_MIN);
        return v < 0 ? 0 : v;
    }

    /** يُستدعى بعد تغيير مدة الخمول من الإعدادات. */
    public static void rescheduleIdle(Context ctx) {
        synchronized (LOCK) { scheduleIdleLocked(ctx.getApplicationContext()); }
        BuildService s = inst;
        if (s != null) s.refresh();
    }

    // يُستدعى داخل synchronized(LOCK): يلغي المؤقت القديم ويبدأ جديدًا إن كان الوضع الدائم مفعّلًا ولا توجد مهام.
    private static void scheduleIdleLocked(final Context app) {
        if (idleTask != null) { IDLE_H.removeCallbacks(idleTask); idleTask = null; }
        if (!isPersistent(app) || tasks > 0) return;
        final int min = idleMinutes(app);
        if (min <= 0) return;
        idleTask = new Runnable() { @Override public void run() { onIdleExpired(app, min); } };
        IDLE_H.postDelayed(idleTask, min * 60_000L);
    }

    private static void onIdleExpired(Context app, int min) {
        synchronized (LOCK) {
            idleTask = null;
            if (tasks > 0 || !isPersistent(app)) return;
        }
        try {
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date());
            java.util.List<String> list = new java.util.ArrayList<>(prefs(app).getStringSet("auto_stop_log", new java.util.LinkedHashSet<String>()));
            list.add(ts + "|idle|-|" + min);
            while (list.size() > 100) list.remove(0);
            prefs(app).edit().putStringSet("auto_stop_log", new java.util.LinkedHashSet<>(list)).apply();
        } catch (Throwable ignored) {}
        prefs(app).edit().putBoolean(KEY_BG, false).apply();
        syncWatchdog(app);
        notifyIdleStop(app, min);
        halt(app);
    }

    private static void notifyIdleStop(Context app, int min) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(AUTOSTOP_CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        AUTOSTOP_CHANNEL_ID, "إيقاف تلقائي للبناء/الأوامر", NotificationManager.IMPORTANCE_HIGH));
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(app, AUTOSTOP_CHANNEL_ID) : new Notification.Builder(app);
            b.setContentTitle("أُوقف وضع الخلفية تلقائيًا")
                    .setContentText("لا توجد عملية جارية منذ " + min + " دقيقة. شغّله من السويتش عند الحاجة.")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setAutoCancel(true);
            nm.notify((int) System.currentTimeMillis(), b.build());
        } catch (Throwable ignored) {}
    }

    /** بداية مهمة طويلة. */
    public static void start(Context ctx) {
        synchronized (LOCK) {
            tasks++;
            scheduleIdleLocked(ctx.getApplicationContext());
            launch(ctx);
        }
    }

    /** نهاية مهمة طويلة — لا تُوقف الخدمة إن كان وضع الخلفية الدائم مفعّلًا. */
    public static void stop(Context ctx) {
        synchronized (LOCK) {
            if (tasks > 0) tasks--;
            if (tasks <= 0) clearTaskNote(ctx);
            scheduleIdleLocked(ctx.getApplicationContext());
            if (tasks <= 0 && !isPersistent(ctx)) halt(ctx);
            else {
                BuildService s = inst;
                if (s != null) s.refresh();
            }
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void launch(Context ctx) {
        try {
            Intent i = new Intent(ctx, BuildService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
            // إن منع النظام بدء الخدمة تستمر المهمة بأولوية عادية بدل توقف التطبيق.
        }
    }

    private static void halt(Context ctx) {
        try { ctx.stopService(new Intent(ctx, BuildService.class)); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ دورة حياة الخدمة

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        ensureChannel();
        goForeground();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            // زر «إيقاف» في الإشعار: يُطفئ وضع الخلفية الدائم.
            prefs(this).edit().putBoolean(KEY_BG, false).apply();
            syncWatchdog(this);
            synchronized (LOCK) {
                if (tasks <= 0) { stopSelf(); return START_NOT_STICKY; }
            }
            refresh();
            return START_NOT_STICKY;
        }
        goForeground();
        acquireLocks();
        synchronized (LOCK) { scheduleIdleLocked(getApplicationContext()); }
        syncWatchdog(this);
        // أعاد النظام الخدمة بعد قتل العملية (intent == null): إن كانت هناك عملية انقطعت ننبّه المستخدم.
        if (intent == null && hasInterrupted(this)) {
            if (!tryAutoResume(getApplicationContext())) notifyInterrupted(getApplicationContext());
        }
        // إن لم تكن هناك مهمة ولا وضع دائم نُنهي الخدمة بعد دقيقتين (حتى لا تبقى معلّقة بعد إعادة تشغيلها).
        if (!isPersistent(this)) {
            final BuildService me = this;
            IDLE_H.postDelayed(new Runnable() { @Override public void run() {
                synchronized (LOCK) {
                    if (tasks <= 0 && !isPersistent(me) && inst == me) me.stopSelf();
                }
            } }, 120_000L);
        }
        // START_STICKY: إن قتل النظام العملية يعيد تشغيل الخدمة (intent == null) طالما الوضع مفعّل
        // أو توجد عملية جارية مسجّلة (لتُستأنف تلقائيًا بدون فتح التطبيق).
        boolean rec = !prefs(this).getString("rt_kind", "").isEmpty();
        return (isPersistent(this) || rec) ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // سحب التطبيق من قائمة التطبيقات الأخيرة لا يوقف الخدمة (كما في Termux).
        // وبعض الأجهزة تقتل العملية عندها رغم الخدمة الأمامية؛ لذلك نضبط منبّهًا سريعًا يعيد تشغيلها.
        if (isPersistent(this)) armWatchdog(getApplicationContext(), 3000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ الأقفال

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "L2SBuilder:bg");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {}
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= 29
                            ? WifiManager.WIFI_MODE_FULL_HIGH_PERF : WifiManager.WIFI_MODE_FULL;
                    wifiLock = wm.createWifiLock(mode, "L2SBuilder:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
        wifiLock = null;
    }

    // ------------------------------------------------------------------ الإشعار

    private void refresh() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    private void goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable ignored) {}
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try { nm.deleteNotificationChannel(OLD_CHANNEL_ID); nm.deleteNotificationChannel(OLD_CHANNEL_ID0); } catch (Throwable ignored) {}
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "التشغيل في الخلفية", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    ch.setSound(null, null);
                    ch.enableVibration(false);
                    ch.enableLights(false);
                    ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                    nm.createNotificationChannel(ch);
                }
            }
        }
    }

    private Notification buildNotification() {
        boolean bg = isPersistent(this);
        boolean working;
        synchronized (LOCK) { working = tasks > 0; }
        String text = working
                ? (bg ? "وضع الخلفية مفعّل — عملية جارية الآن..." : "عملية بناء أو تنفيذ أمر جارية في الخلفية...")
                : "وضع الخلفية مفعّل — التطبيق محمي من الإغلاق";
        String st = statusText;
        if (working && st != null && !st.isEmpty()) text = st;
        int idleMin = idleMinutes(this);
        if (!working && bg && idleMin > 0) text += " (يتوقف تلقائيًا بعد " + idleMin + " د من الخمول)";

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("L2S Builder")
                .setContentText(text)
                .setSmallIcon(working ? android.R.drawable.stat_sys_download : android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);

        if (bg) {
            PendingIntent exit = PendingIntent.getService(this, 1,
                    new Intent(this, BuildService.class).setAction(ACTION_EXIT), piFlags);
            b.addAction(new Notification.Action.Builder(null, "إيقاف وضع الخلفية", exit).build());
        }
        return b.build();
    }
}
