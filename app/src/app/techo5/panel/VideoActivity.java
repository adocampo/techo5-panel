package app.techo5.panel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.VideoView;

/**
 * A video echod asked for, full screen: Android's own player decodes it, and every change goes back
 * over the control line — "video state &lt;id&gt; &lt;phase&gt;", "video pos", "video ended", "video
 * error" — which is what Home Assistant and Video Assistant read. echod only mirrors what this says:
 * the truth about what the tablet is showing is the tablet's, and a reconnect finds it by resync.
 *
 * <p>One at a time (singleTask): a new play takes the screen from the old one. A tap shows or hides
 * a bar with Stop; pause, resume and the rest come from Home Assistant, not from the screen. When
 * the video ends, fails or is stopped the activity closes itself and the clock comes back.</p>
 */
public class VideoActivity extends Activity {
    private static final String TAG = "techo5-panel";
    private static final long POS_EVERY_MS = 5000;

    private static volatile VideoActivity live;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video;
    private View bar;
    private AudioManager audio;
    private String id = "";
    private volatile String phase = "loading";
    // paused by onPause rather than by anybody asking, so coming back carries it on by itself.
    private boolean heldByPause;
    private boolean ended;

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int loss) {
            if (loss == AudioManager.AUDIOFOCUS_LOSS || loss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                ui.post(new Runnable() {
                    @Override public void run() { pause(); }
                });
            }
        }
    };

    private final Runnable pos = new Runnable() {
        @Override
        public void run() {
            tellPos();
            ui.postDelayed(this, POS_EVERY_MS);
        }
    };

    /** echod's "video play &lt;id&gt; &lt;url&gt; &lt;title&gt;": open, or hand the new video to the one up. */
    static void open(Context ctx, String[] w) {
        Intent i = new Intent(ctx, VideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("id", w[2]);
        i.putExtra("url", w[3]);
        StringBuilder title = new StringBuilder();
        for (int k = 4; k < w.length; k++) {
            if (k > 4) title.append(' ');
            title.append(w[k]);
        }
        String t = title.toString();
        i.putExtra("title", "\"\"".equals(t) ? "" : t);
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "video: cannot open: " + e.getMessage());
        }
    }

    /** echod's "video pause", "video resume" and "video stop", to the video up, if there is one. */
    static void command(final String what) {
        final VideoActivity v = live;
        if (v == null) return;
        v.ui.post(new Runnable() {
            @Override
            public void run() {
                if (what.equals("pause")) v.pause();
                else if (what.equals("resume")) v.resume();
                else if (what.equals("stop")) v.stopAndClose();
            }
        });
    }

    /** echod connected again: bring it up to date with what is showing, if anything is. */
    static void resync() {
        VideoActivity v = live;
        if (v == null || v.ended) return;
        ControlLink.tell("video state " + v.id + " " + v.phase);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = getSystemService(AudioManager.class);
        live = this;
        build();
        play(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ended = false;
        heldByPause = false;
        play(intent);
    }

    private void build() {
        video = new VideoView(this);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopAndClose(); }
        });
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        at.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        at.bottomMargin = (int) (24 * getResources().getDisplayMetrics().density);
        bar = new FrameLayout(this);
        ((FrameLayout) bar).setBackgroundColor(0x80000000);
        ((FrameLayout) bar).addView(stop, at);
        bar.setVisibility(View.GONE);
        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        // VideoView shrinks itself to the video's aspect ratio; a FrameLayout would park that box
        // top-left, leaving all the black in one band at the bottom. Center it so a letterbox is
        // even, top and bottom, like every other player.
        vlp.gravity = Gravity.CENTER;
        root.addView(video, vlp);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        setContentView(root);
    }

    private void play(Intent i) {
        id = i.getStringExtra("id");
        String url = i.getStringExtra("url");
        String title = i.getStringExtra("title");
        Log.i(TAG, "video: playing " + (title == null || title.isEmpty() ? url : title));
        video.stopPlayback();
        phase = "loading";
        ControlLink.tell("video state " + id + " loading");
        audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                video.start();
                phase = "playing";
                ControlLink.tell("video state " + id + " playing");
                ui.removeCallbacks(pos);
                ui.postDelayed(pos, POS_EVERY_MS);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                ended = true;
                ControlLink.tell("video ended " + id);
                finish();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                ended = true;
                ControlLink.tell("video error " + id + " " + why(what, extra));
                finish();
                return true;
            }
        });
        video.setVideoURI(Uri.parse(url));
    }

    /** MediaPlayer's codes in words a person can read, and Video Assistant's auto-heal acts on. */
    private static String why(int what, int extra) {
        switch (extra) {
            case -2147483648: return "Android cannot decode it (what " + what + ")";
            case -1010: return "the stream took too long";
            case -1008: return "the stream is not supported";
            case -1007: return "the stream is malformed";
            case -1005: return "the stream could not be validated";
            case -1004: return "the stream could not be read";
            default: return "MediaPlayer error " + what + "/" + extra;
        }
    }

    private void pause() {
        if (video == null || ended || !video.isPlaying()) return;
        video.pause();
        phase = "paused";
        ControlLink.tell("video state " + id + " paused");
    }

    private void resume() {
        if (video == null || ended || video.isPlaying()) return;
        video.start();
        phase = "playing";
        ControlLink.tell("video state " + id + " playing");
    }

    /** stopped by a hand or by the back key: echod hears idle, and the clock comes back. */
    private void stopAndClose() {
        if (ended) { finish(); return; }
        ended = true;
        ControlLink.tell("video state " + id + " idle");
        finish();
    }

    private void tellPos() {
        if (video == null || ended || !video.isPlaying()) return;
        ControlLink.tell("video pos " + id + " " + (video.getCurrentPosition() / 1000)
                + " " + (video.getDuration() / 1000));
    }

    @Override
    public void onBackPressed() {
        stopAndClose();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) {
            pause();
            heldByPause = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (heldByPause) {
            heldByPause = false;
            resume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (live == this) live = null;
        ui.removeCallbacks(pos);
        audio.abandonAudioFocus(focus);
        if (video != null) video.stopPlayback();
    }
}
