package com.rightnow.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TickReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Scheduler.ACTION_DONE.equals(a)) {
            String id = i.getStringExtra("id"), day = i.getStringExtra("day");
            if (id != null && day != null) Store.toggleDone(c, day, id);
        } else {
            Scheduler.notifyDue(c);
        }
        Scheduler.refresh(c);
    }
}
