package com.rightnow.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sends block alerts and keeps the widget up to date. */
public final class Scheduler {
    private Scheduler() {}

    static final String CHANNEL = "routine";
    static final String ACTION_TICK = "com.rightnow.app.TICK";
    static final String ACTION_DONE = "com.rightnow.app.DONE";

    /** Update the widget and set the next wake-up (the next time a block starts or ends). */
    static void refresh(Context c) {
        updateWidgets(c);
        scheduleNext(c);
    }

    static void scheduleNext(Context c) {
        LocalDate today = LocalDate.now();
        LocalTime t = LocalTime.now();
        int mins = t.getHour() * 60 + t.getMinute();
        int best = 24 * 60; // midnight: a new day starts
        for (Store.Block b : Store.blocksFor(c, today)) {
            if (b.s > mins && b.s < best) best = b.s;
            if (b.e > mins && b.e < best) best = b.e;
        }
        LocalDateTime when = best >= 24 * 60
                ? today.plusDays(1).atStartOfDay().plusSeconds(5)
                : today.atStartOfDay().plusMinutes(best).plusSeconds(1);
        long at = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, TickReceiver.class).setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Routine alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("A heads-up when a block in your routine starts");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** Alert for any block (with Alert on) that starts right about now. */
    static void notifyDue(Context c) {
        LocalDate today = LocalDate.now();
        LocalTime t = LocalTime.now();
        int mins = t.getHour() * 60 + t.getMinute();
        boolean h24 = Store.clock24(c);
        SharedPreferences p = Store.prefs(c);
        Set<String> sent = new HashSet<>(p.getStringSet("sent", new HashSet<>()));
        Set<String> keep = new HashSet<>();
        for (String k : sent) if (k.startsWith(today.toString())) keep.add(k);

        List<Store.Block> rows = Store.blocksFor(c, today);
        for (Store.Block b : rows) {
            if (!b.alert) continue;
            if (b.s < mins - 3 || b.s > mins + 1) continue;
            String key = today + "|" + b.id + "|" + b.s;
            if (keep.contains(key)) continue;
            keep.add(key);
            String body = Store.fmt(b.s, h24) + " – " + Store.fmt(b.e, h24) + (b.tip.isEmpty() ? "" : "  ·  " + b.tip);
            post(c, (b.id + b.s).hashCode(), (b.important ? "★ " : "") + b.name, body);
        }
        p.edit().putStringSet("sent", keep).apply();
    }

    static void post(Context c, int id, String title, String body) {
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        try { c.getSystemService(NotificationManager.class).notify(id, n); } catch (SecurityException ignored) {}
    }

    static void updateWidgets(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, RoutineWidget.class));
        if (ids == null || ids.length == 0) return;
        Store.Now n = Store.now(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.label, n.label);
        v.setTextColor(R.id.label, n.color);
        v.setTextViewText(R.id.name, n.name);
        v.setTextViewText(R.id.range, n.range);
        v.setTextViewText(R.id.next, n.next);
        v.setTextViewText(R.id.count, n.count);
        if (n.total > 0) {
            v.setViewVisibility(R.id.prog, View.VISIBLE);
            v.setProgressBar(R.id.prog, n.total, n.done, false);
        } else {
            v.setViewVisibility(R.id.prog, View.GONE);
        }

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        if (n.doneId != null) {
            v.setViewVisibility(R.id.done, View.VISIBLE);
            v.setTextViewText(R.id.done, n.isDone ? "Done ✓" : "✓ Mark done");
            Intent d = new Intent(c, TickReceiver.class).setAction(ACTION_DONE)
                    .putExtra("id", n.doneId).putExtra("day", LocalDate.now().toString());
            v.setOnClickPendingIntent(R.id.done, PendingIntent.getBroadcast(c, 2, d, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            v.setViewVisibility(R.id.done, View.GONE);
        }
        mgr.updateAppWidget(ids, v);
    }
}
