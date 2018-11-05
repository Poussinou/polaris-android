package agersant.polaris;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import agersant.polaris.api.API;
import agersant.polaris.api.FetchImageTask;
import agersant.polaris.features.player.PlayerActivity;


public class PolarisPlaybackService extends Service {

	private static final int MEDIA_NOTIFICATION = 1;
	private static final String MEDIA_INTENT_PAUSE = "MEDIA_INTENT_PAUSE";
	private static final String MEDIA_INTENT_PLAY = "MEDIA_INTENT_PLAY";
	private static final String MEDIA_INTENT_SKIP_NEXT = "MEDIA_INTENT_SKIP_NEXT";
	private static final String MEDIA_INTENT_SKIP_PREVIOUS = "MEDIA_INTENT_SKIP_PREVIOUS";
	private static final String MEDIA_INTENT_DISMISS = "MEDIA_INTENT_DISMISS";

	private static final String NOTIFICATION_CHANNEL_ID = "POLARIS_NOTIFICATION_CHANNEL_ID";

	private final IBinder binder = new PolarisPlaybackService.PolarisBinder();
	private BroadcastReceiver receiver;
	private Notification notification;
	private CollectionItem notificationItem;

	private API api;
	private PolarisPlayer player;
	private PlaybackQueue playbackQueue;

	@Override
	public void onCreate() {
		super.onCreate();

		PolarisState state = PolarisApplication.getState();
		api = state.api;
		player = state.player;
		playbackQueue = state.playbackQueue;

		pushSystemNotification();

		restoreStateFromDisk();

		IntentFilter filter = new IntentFilter();
		filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
		filter.addAction(PolarisPlayer.PLAYING_TRACK);
		filter.addAction(PolarisPlayer.PAUSED_TRACK);
		filter.addAction(PolarisPlayer.RESUMED_TRACK);
		filter.addAction(PolarisPlayer.PLAYBACK_ERROR);
		filter.addAction(PolarisPlayer.COMPLETED_TRACK);
		receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				switch (intent.getAction()) {
					case PolarisPlayer.PLAYBACK_ERROR:
						displayError();
						break;
					case PolarisPlayer.PLAYING_TRACK:
					case PolarisPlayer.RESUMED_TRACK:
						pushSystemNotification();
						break;
					case PolarisPlayer.PAUSED_TRACK:
						pushSystemNotification();
						saveStateToDisk();
						break;
					case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
						player.pause();
						break;
				}
			}
		};
		registerReceiver(receiver, filter);
	}

	@Override
	public void onDestroy() {
		unregisterReceiver(receiver);
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	public class PolarisBinder extends Binder {	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		handleIntent(intent);
		super.onStartCommand(intent, flags, startId);
		return START_NOT_STICKY;
	}

	// Internals
	private void displayError() {
		Toast toast = Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT);
		toast.show();
	}

	private void handleIntent(Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return;
		}
		String action = intent.getAction();
		switch (action) {
			case MEDIA_INTENT_PAUSE:
				player.pause();
				break;
			case MEDIA_INTENT_PLAY:
				player.resume();
				break;
			case MEDIA_INTENT_SKIP_NEXT:
				player.skipNext();
				break;
			case MEDIA_INTENT_SKIP_PREVIOUS:
				player.skipPrevious();
				break;
			case MEDIA_INTENT_DISMISS:
				stopSelf();
				break;
		}
	}

	private void pushSystemNotification() {

		boolean isPlaying = player.isPlaying();
		final CollectionItem item = player.getCurrentItem();
		if (item == null) {
			return;
		}

		// On tap action
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this)
				.addParentStack(PlayerActivity.class)
				.addNextIntent(new Intent(this, PlayerActivity.class));
		PendingIntent tapPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

		// On dismiss action
		Intent dismissIntent = new Intent(this, PolarisPlaybackService.class);
		dismissIntent.setAction(MEDIA_INTENT_DISMISS);
		PendingIntent dismissPendingIntent = PendingIntent.getService(this, 0, dismissIntent, 0);

		// Create notification
		final Notification.Builder notificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
				.setShowWhen(false)
				.setSmallIcon(R.drawable.notification_icon)
				.setContentTitle(item.getTitle())
				.setContentText(item.getArtist())
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setContentIntent(tapPendingIntent)
				.setDeleteIntent(dismissPendingIntent)
				.setStyle(new Notification.MediaStyle()
						.setShowActionsInCompactView()
				);

		// Add album art
		if (item == notificationItem && notification != null && notification.getLargeIcon() != null) {
			notificationBuilder.setLargeIcon(notification.getLargeIcon());
		}
		if (item.getArtwork() != null) {
			api.loadImage(item, new FetchImageTask.Callback() {
				@Override
				public void onSuccess(Bitmap bitmap) {
					if (item != player.getCurrentItem()) {
						return;
					}
					notificationBuilder.setLargeIcon(bitmap);
					emitNotification(notificationBuilder, item);
				}
			});
		}

		// Add media control actions
		notificationBuilder.addAction(generateAction(R.drawable.ic_skip_previous_black_24dp, R.string.player_next_track, MEDIA_INTENT_SKIP_PREVIOUS));
		if (isPlaying) {
			notificationBuilder.addAction(generateAction(R.drawable.ic_pause_black_24dp, R.string.player_pause, MEDIA_INTENT_PAUSE));
		} else {
			notificationBuilder.addAction(generateAction(R.drawable.ic_play_arrow_black_24dp, R.string.player_play, MEDIA_INTENT_PLAY));
		}
		notificationBuilder.addAction(generateAction(R.drawable.ic_skip_next_black_24dp, R.string.player_previous_track, MEDIA_INTENT_SKIP_NEXT));

		// Emit notification
		emitNotification(notificationBuilder, item);

		if (isPlaying) {
			startForeground(MEDIA_NOTIFICATION, notification);
		} else {
			stopForeground(false);
		}
	}

	private void emitNotification(Notification.Builder notificationBuilder, CollectionItem item) {
		notificationItem = item;
		notification = notificationBuilder.build();
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel mChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Polaris", NotificationManager.IMPORTANCE_DEFAULT);
		mChannel.setDescription("Notifications for current song playing in Polaris.");
		mChannel.enableLights(false);
		mChannel.enableVibration(false);
		mChannel.setShowBadge(false);
		notificationManager.createNotificationChannel(mChannel);
		notificationManager.notify(MEDIA_NOTIFICATION, notification);
	}

	private Notification.Action generateAction(int icon, int text, String intentAction) {
		Intent intent = new Intent(this, PolarisPlaybackService.class);
		intent.setAction(intentAction);
		PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 0);
		return new Notification.Action.Builder(Icon.createWithResource(this, icon), getResources().getString(text), pendingIntent).build();
	}

	private void saveStateToDisk() {
		File storage = new File(getCacheDir(), "playlist.v" + PlaybackQueueState.VERSION);

		// Gather state
		PlaybackQueueState state = new PlaybackQueueState();
		state.queueContent = playbackQueue.getContent();
		state.queueOrdering = playbackQueue.getOrdering();
		CollectionItem currentItem = player.getCurrentItem();
		state.queueIndex = state.queueContent.indexOf(currentItem);
		state.trackProgress = player.getPositionRelative();

		// Persist
		try (FileOutputStream out = new FileOutputStream(storage)) {
			try (ObjectOutputStream objOut = new ObjectOutputStream(out)) {
				objOut.writeObject(state);
			} catch (IOException e) {
				System.out.println("Error while saving PlaybackQueueState object: " + e);
			}
		} catch (IOException e) {
			System.out.println("Error while writing PlaybackQueueState file: " + e);
		}
	}

	private void restoreStateFromDisk() {
		File storage = new File(getCacheDir(), "playlist.v" + PlaybackQueueState.VERSION);
		try (FileInputStream in = new FileInputStream(storage)) {
			try (ObjectInputStream objIn = new ObjectInputStream(in)) {
				Object obj = objIn.readObject();
				if (obj instanceof PlaybackQueueState) {
					PlaybackQueueState state = (PlaybackQueueState) obj;
					playbackQueue.setContent(state.queueContent);
					playbackQueue.setOrdering(state.queueOrdering);
					if (state.queueIndex >= 0 && player.isIdle()) {
						CollectionItem currentItem = playbackQueue.getItem(state.queueIndex);
						player.play(currentItem);
						player.pause();
						player.seekToRelative(state.trackProgress);
					}
				}
			} catch (ClassNotFoundException e) {
				System.out.println("Error while loading PlaybackQueueState object: " + e);
			}
		} catch (IOException e) {
			System.out.println("Error while reading PlaybackQueueState file: " + e);
		}
	}
}