package com.vidownphone.app.ui.player;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.vidownphone.app.R;
import com.vidownphone.app.databinding.ActivityPlayerBinding;

import java.io.File;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_PATH = "extra_media_path";
    public static final String EXTRA_MEDIA_TITLE = "extra_media_title";

    private ActivityPlayerBinding binding;
    private ExoPlayer exoPlayer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String mediaPath = getIntent().getStringExtra(EXTRA_MEDIA_PATH);
        String mediaTitle = getIntent().getStringExtra(EXTRA_MEDIA_TITLE);

        binding.tvPlayerTitle.setText(mediaTitle != null ? mediaTitle : getString(R.string.player_title));
        binding.btnPlayerBack.setOnClickListener(v -> finish());

        if (mediaPath == null || mediaPath.isEmpty()) {
            Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initializePlayer(mediaPath);
    }

    private void initializePlayer(String pathOrUrl) {
        exoPlayer = new ExoPlayer.Builder(this).build();
        binding.playerView.setPlayer(exoPlayer);

        Uri mediaUri;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("content://")) {
            mediaUri = Uri.parse(pathOrUrl);
        } else {
            File file = new File(pathOrUrl);
            if (!file.exists()) {
                Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            mediaUri = Uri.fromFile(file);
        }

        MediaItem mediaItem = MediaItem.fromUri(mediaUri);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this,
                        getString(R.string.player_error) + ": " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
        exoPlayer.prepare();
        exoPlayer.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
