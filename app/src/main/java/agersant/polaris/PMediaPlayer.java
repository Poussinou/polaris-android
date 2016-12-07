package agersant.polaris;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;

import java.io.IOException;
import java.util.Map;

/**
 * Created by agersant on 12/6/2016.
 */

public class PMediaPlayer
        implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener {

    private MediaPlayer player;
    private State state;
    private boolean pause;

    PMediaPlayer() {
        pause = false;
        state = State.IDLE;
        player = new MediaPlayer();
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        // TODO Handle
        state = State.PLAYBACK_COMPLETED;
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
        // TODO Handle
        state = State.ERROR;
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        state = State.PREPARED;
        if (!pause) {
            state = State.STARTED;
            player.start();
        }
    }

    void reset() {
        state = State.IDLE;
        pause = false;
        player.reset();
    }

    void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException {
        state = State.INITIALIZED;
        player.setDataSource(context, uri, headers);
    }

    void prepareAsync() {
        state = State.PREPARING;
        player.prepareAsync();
    }

    void pause() {
        pause = true;
        switch (state) {
            case STARTED:
                state = State.PAUSED;
                player.pause();
                break;
        }
    }

    void resume() {
        pause = false;
        switch (state) {
            case PREPARED:
            case PAUSED:
                state = State.STARTED;
                player.start();
                break;
        }
    }

    boolean isPlaying() {
        if (pause) {
            return false;
        }
        switch (state) {
            case PREPARING:
            case STARTED:
                return true;
        }
        return false;
    }

    enum State {
        IDLE,
        INITIALIZED,
        PREPARING,
        PREPARED,
        STARTED,
        STOPPED,
        PAUSED,
        PLAYBACK_COMPLETED,
        END,
        ERROR,
    }

}