package de.blinkt.openvpn.core;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import de.blinkt.openvpn.R;

/**
 * مقداردهی اولیه‌ی هسته‌ی OpenVPN برای استفاده از برنامه‌ی میزبان.
 * همان کاری که ICSOpenVPNApplication انجام می‌دهد (کانال‌ها و StatusListener)،
 * اما بدون Application جداگانه تا برنامه‌ی ما Application خودش را داشته باشد.
 */
public final class OvpnBootstrap {
    private static StatusListener sStatus;
    private static boolean sInitialized = false;

    private OvpnBootstrap() {
    }

    public static synchronized void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels(app);
        }
        sStatus = new StatusListener();
        sStatus.init(app);
    }

    private static void createNotificationChannels(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        CharSequence name = context.getString(R.string.channel_name_background);
        NotificationChannel ch = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
                name, NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(context.getString(R.string.channel_description_background));
        ch.enableLights(false);
        ch.setLightColor(Color.DKGRAY);
        nm.createNotificationChannel(ch);

        name = context.getString(R.string.channel_name_status);
        ch = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
                name, NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(context.getString(R.string.channel_description_status));
        ch.enableLights(true);
        ch.setLightColor(Color.BLUE);
        nm.createNotificationChannel(ch);

        name = context.getString(R.string.channel_name_userreq);
        ch = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
                name, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(context.getString(R.string.channel_description_userreq));
        ch.enableVibration(true);
        ch.setLightColor(Color.CYAN);
        nm.createNotificationChannel(ch);
    }
}
