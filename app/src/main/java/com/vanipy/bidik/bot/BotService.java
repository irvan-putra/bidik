package com.vanipy.bidik.bot;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * DeltaChat-backed transport service.
 *
 * GATED: never auto-starts. It runs only when the user has explicitly
 * completed transport setup in the app (pairing a trusted contact), which
 * is a deliberate action - the app creates no accounts on first launch and
 * public builds ship with the transport disabled until the pairing workflow
 * is approved. The chatmail account, when created, is minted
 * by an explicit button in the setup screen (same provisioning pattern as
 * itdapk's bot: dcaccount: relay QR, keys only in the app-private dir).
 *
 * The python runtime lives in app/src/main/python/dc_transport.py.
 */
public class BotService extends Service {
    public static final String ACTION_START = "com.vanipy.bidik.bot.START";
    public static final String ACTION_STOP = "com.vanipy.bidik.bot.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Scaffold: runtime wiring lands after the transport pairing
        // workflow is approved. Until then this service stops immediately.
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
