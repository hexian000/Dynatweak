package org.hexian000.dynatweak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

import static org.hexian000.dynatweak.DynatweakApp.LOG_TAG;

public class TweakService extends Service {
	public final static String CHANNEL_APPLYING_SETTINGS = "applying_settings";

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Notification.Builder builder = new Notification.Builder(this.getApplicationContext());
		builder.setContentIntent(null)
		       .setContentTitle(getResources().getString(R.string.applying_settings))
		       .setSmallIcon(R.drawable.ic_settings_black_24dp)
		       .setWhen(System.currentTimeMillis())
		       .setProgress(0, 0, true)
		       .setOngoing(true);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			builder.setVisibility(Notification.VISIBILITY_PUBLIC);
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// Android 8.0+
			NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (manager != null) {
				NotificationChannel channel = new NotificationChannel(CHANNEL_APPLYING_SETTINGS,
						getResources().getString(R.string.applying_settings), NotificationManager.IMPORTANCE_DEFAULT);
				channel.enableLights(false);
				channel.enableVibration(false);
				channel.setSound(null, null);

				manager.createNotificationChannel(channel);
				builder.setChannelId(CHANNEL_APPLYING_SETTINGS);
			}
		} else {
			builder.setPriority(Notification.PRIORITY_DEFAULT)
			       .setLights(0, 0, 0)
			       .setVibrate(null)
			       .setSound(null);
		}

		startForeground(startId, builder.build());

		final int hotplug = intent.getIntExtra("hotplug", BootReceiver.HOTPLUG_ALLCORES);
		final int profile = intent.getIntExtra("profile", BootReceiver.PROFILE_BALANCED);
		final Handler handler = new Handler();
		new Thread(() -> {
			try {
				BootReceiver.tweak(hotplug, profile);
				handler.post(() ->
						Toast.makeText(TweakService.this, R.string.boot_success, Toast.LENGTH_SHORT).show());
			} catch (IOException e) {
				Log.e(LOG_TAG, "TweakService exception", e);
				handler.post(() ->
						Toast.makeText(TweakService.this, R.string.boot_failed, Toast.LENGTH_SHORT).show());
			} finally {
				handler.post(TweakService.this::stopSelf);
			}
		});
		return START_NOT_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
