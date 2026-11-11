package com.rightnow.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class RoutineWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Scheduler.refresh(c);
    }

    @Override
    public void onEnabled(Context c) {
        Scheduler.refresh(c);
    }
}
