package art.xandy.cut;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.util.Base64;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;
import androidx.media3.effect.RgbMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Xandy Cut 1.2: edicao multipistas, transicoes e biblioteca de audio integrada.
 * Usa rede apenas quando o usuario abre a biblioteca online. Sem anuncios ou telemetria.
 */
@UnstableApi
public final class MainActivity extends Activity {
    private static final int PICK_VIDEOS = 101, PICK_MUSIC = 102;
    private static final int BG = Color.rgb(12, 13, 20);
    private static final int CARD = Color.rgb(27, 29, 41);
    private static final int DARK = Color.rgb(39, 42, 58);
    private static final int CYAN = Color.rgb(48, 224, 209);
    private static final int PURPLE = Color.rgb(174, 129, 252);
    private static final int GREEN = Color.rgb(86, 214, 161);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(170, 178, 198);

    private static final String[] TRANSITIONS = {"Corte seco", "Escurecer", "Clarão branco", "Neon ciano"};
    private static final long TRANSITION_MS = 420L;
    private static final String ONLINE_URL =
        "https://raw.githubusercontent.com/alextelles0023-beep/XandyCut/main/audio/catalog.json";
    private static final String ONLINE_BASE =
        "https://github.com/alextelles0023-beep/XandyCut/releases/download/v1.2-beta/";
    private static final String[] BUILTIN_NAMES = {
        "Cinema épico", "Suspense escuro", "Lo-fi tranquilo", "Afro groove", "Piano suave",
        "Neon futurista", "Whoosh de transição", "Impacto dramático", "Sino mágico"
    };
    private static final String[] BUILTIN_FILES = {
        "cinema", "suspense", "lofi", "afrogroove", "piano", "neon", "whoosh", "impact", "sino"
    };
    private static final String[] BUILTIN_GROUPS = {
        "Cinemático", "Suspense", "Chill", "Ritmo", "Piano",
        "Eletrônico", "Efeito sonoro", "Efeito sonoro", "Efeito sonoro"
    };
    private static class AudioEntry {
        final String title, slug, group;
        final boolean online;
        AudioEntry(String title, String slug, String group, boolean online) {
            this.title = title; this.slug = slug; this.group = group; this.online = online;
        }
    }
    private static class Clip {
        Uri uri;
        String name;
        long durationMs, fromMs, toMs;
        String transition = TRANSITIONS[0];
        Clip(Uri uri, String name, long durationMs) {
            this.uri = uri;
            this.name = name;
            this.durationMs = durationMs;
            this.fromMs = 0;
            this.toMs = durationMs;
        }
        long length() { return toMs - fromMs; }
    }

    private static class SoundTrack {
        Uri uri;
        String name;
        long durationMs, inMs, outMs, startMs;
        int volume = 65;
        boolean repeat = false;
        ExoPlayer player;
        SoundTrack(Uri uri, String name, long durationMs, long startMs) {
            this.uri = uri;
            this.name = name;
            this.durationMs = durationMs;
            this.inMs = 0;
            this.outMs = durationMs;
            this.startMs = startMs;
        }
        long length() { return outMs - inMs; }
    }

    private final ArrayList<Clip> clips = new ArrayList<>();
    private final ArrayList<SoundTrack> sounds = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ProgressHolder progressHolder = new ProgressHolder();
    private ExoPlayer videoPlayer;
    private Transformer transformer;
    private File tempFile;
    private Uri lastVideo;
    private boolean exporting, scrubbing;
    private int originalVolume = 55;
    private long lastPreviewMs = -1;
    private int selectedClip = -1;

    private LinearLayout clipTimeline, clipActions, soundTimeline, soundActions;
    private TextView timeLabel, summaryLabel, statusLabel, soundsLabel, clipLabel;
    private SeekBar playhead, originalSeek;
    private Button playButton, exportButton, shareButton, cancelButton;
    private ProgressBar progressBar;
    private HorizontalScrollView stripScroll;
    private TextView originalLabel, onlineStatus;
    private View transitionMask;
    private ExoPlayer catalogPlayer;
    private boolean catalogLoading = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        videoPlayer = new ExoPlayer.Builder(this).build();
        videoPlayer.setVolume(0.55f);
        buildUi();
        restoreDraft();
        redraw();
        handler.post(previewTick);
    }

    private void buildUi() {
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setBackgroundColor(BG);
        LinearLayout root = vertical();
        root.setPadding(dp(14), dp(14), dp(14), dp(32));
        screen.addView(root);
        setContentView(screen);

        TextView title = text("XANDY CUT", 27, CYAN, true);
        root.addView(title);
        root.addView(text("ESTÚDIO DE MONTAGEM • V1.2", 11, MUTED, true), margin(0, 3));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        root.addView(previewFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(266)));
        PlayerView playerView = new PlayerView(this);
        playerView.setPlayer(videoPlayer);
        playerView.setUseController(false);
        playerView.setBackgroundColor(Color.BLACK);
        previewFrame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        transitionMask = new View(this);
        transitionMask.setClickable(false);
        transitionMask.setAlpha(0f);
        previewFrame.addView(transitionMask, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout playback = horizontal();
        playback.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(playback, margin(0, 9));
        playButton = button("▶", CYAN);
        playback.addView(playButton, new LinearLayout.LayoutParams(dp(52), dp(48)));
        playButton.setOnClickListener(v -> togglePlayback());
        timeLabel = text("00:00 / 00:00", 12, WHITE, true);
        timeLabel.setGravity(Gravity.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(44), 1);
        timeLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        playback.addView(timeLabel, tp);

        playhead = new SeekBar(this);
        playhead.setMax(1000);
        playhead.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        playhead.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
        root.addView(playhead);
        playhead.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar sb) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seekTimeline(totalMs() * sb.getProgress() / 1000);
                scrubbing = false;
            }
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) timeLabel.setText(formatMs(totalMs() * progress / 1000) + " / " + formatMs(totalMs()));
            }
        });

        LinearLayout imports = horizontal();
        root.addView(imports, margin(0, 11));
        equalButton(imports, "+  VÍDEOS", CYAN, () -> pick("video/*", true, PICK_VIDEOS));
        equalButton(imports, "+  ÁUDIO", PURPLE, () -> pick("audio/*", true, PICK_MUSIC));
        LinearLayout catalogRow = horizontal();
        root.addView(catalogRow, margin(0, 6));
        equalButton(catalogRow, "♪ BIBLIOTECA", GREEN, () -> openLibrary(false));
        equalButton(catalogRow, "☁ ONLINE", DARK, () -> openLibrary(true));
        onlineStatus = text("Áudios inclusos • internet opcional para novas músicas", 11, MUTED, false);
        root.addView(onlineStatus, margin(0, 5));

        summaryLabel = text("", 15, WHITE, true);
        root.addView(summaryLabel, margin(0, 23));
        root.addView(text("Toque num clipe para editar. Deslize para ver a sequência.", 11, MUTED, false), margin(0, 5));
        stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        root.addView(stripScroll, margin(0, 10));
        clipTimeline = horizontal();
        stripScroll.addView(clipTimeline);
        clipLabel = text("", 12, MUTED, false);
        root.addView(clipLabel, margin(0, 10));
        clipActions = vertical();
        root.addView(clipActions, margin(0, 8));

        TextView h = text("TRILHAS SONORAS", 16, WHITE, true);
        root.addView(h, margin(0, 22));
        soundsLabel = text("Adicione músicas ou efeitos sonoros em faixas separadas.", 12, MUTED, false);
        root.addView(soundsLabel, margin(0, 7));
        HorizontalScrollView musicScroll = new HorizontalScrollView(this);
        musicScroll.setHorizontalScrollBarEnabled(false);
        root.addView(musicScroll, margin(0, 10));
        soundTimeline = vertical();
        musicScroll.addView(soundTimeline);
        soundActions = vertical();
        root.addView(soundActions, margin(0, 10));

        originalLabel = text("ÁUDIO ORIGINAL DOS VÍDEOS  •  55%", 13, WHITE, true);
        root.addView(originalLabel, margin(0, 18));
        originalSeek = new SeekBar(this);
        originalSeek.setMax(100);
        originalSeek.setProgress(originalVolume);
        originalSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        root.addView(originalSeek);
        originalSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean user) {
                originalVolume = p;
                originalLabel.setText("ÁUDIO ORIGINAL DOS VÍDEOS  •  " + p + "%");
                if (videoPlayer != null) videoPlayer.setVolume(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { saveDraft(); }
        });

        exportButton = button("EXPORTAR VÍDEO MP4", CYAN);
        exportButton.setTextSize(16);
        root.addView(exportButton, margin(0, 24));
        exportButton.setOnClickListener(v -> export());
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        root.addView(progressBar, margin(0, 10));
        statusLabel = text("Montagem salva automaticamente neste aparelho.", 12, MUTED, false);
        root.addView(statusLabel, margin(0, 7));
        cancelButton = button("Cancelar exportação", DARK);
        cancelButton.setTextColor(WHITE);
        cancelButton.setVisibility(View.GONE);
        root.addView(cancelButton, margin(0, 9));
        cancelButton.setOnClickListener(v -> cancelExport());
        shareButton = button("Compartilhar último vídeo", PURPLE);
        shareButton.setEnabled(false);
        root.addView(shareButton, margin(0, 10));
        shareButton.setOnClickListener(v -> share());
        root.addView(text("Áudios originais do Xandy Cut • sem anúncios • sem marca-d'água. Os vídeos são editados no celular; a internet é usada apenas para baixar novos sons.", 11, MUTED, false), margin(0, 20));
    }

    private void pick(String mime, boolean multi, int request) {
        if (exporting) { toast("Conclua ou cancele a exportação primeiro."); return; }
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType(mime);
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(it, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || exporting) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        int ok = 0;
        long insertion = currentTimelineMs();
        for (Uri uri : uris) {
            persistPermission(uri);
            long duration = duration(uri);
            if (duration <= 100) continue;
            if (request == PICK_VIDEOS) {
                clips.add(new Clip(uri, displayName(uri), duration));
                ok++;
            } else if (request == PICK_MUSIC) {
                if (sounds.size() >= 6) { toast("Máximo de 6 faixas para evitar sobrecarregar o celular."); break; }
                SoundTrack track = new SoundTrack(uri, displayName(uri), duration, Math.min(insertion, totalMs()));
                sounds.add(track);
                ok++;
            }
        }
        if (ok == 0) toast("Não foi possível ler a duração dos arquivos escolhidos.");
        if (request == PICK_VIDEOS) {
            selectedClip = Math.max(0, clips.size() - 1);
            reloadVideo(0);
        }
        redraw();
        saveDraft();
    }

    private void persistPermission(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "Arquivo" : uri.getLastPathSegment();
    }
    private long duration(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if ("file".equals(uri.getScheme())) mmr.setDataSource(uri.getPath());
            else mmr.setDataSource(this, uri);
            String v = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception ignored) { return 0; }
        finally { try { mmr.release(); } catch (Exception ignored) { } }
    }

    private long totalMs() { long sum = 0; for (Clip c : clips) sum += c.length(); return sum; }
    private long currentTimelineMs() {
        if (videoPlayer == null || clips.isEmpty()) return 0;
        int pos = videoPlayer.getCurrentMediaItemIndex();
        if (pos < 0) return 0;
        long sum = 0;
        for (int i = 0; i < Math.min(pos, clips.size()); i++) sum += clips.get(i).length();
        return Math.min(totalMs(), sum + Math.max(0, videoPlayer.getCurrentPosition()));
    }

    private void reloadVideo(long where) {
        if (videoPlayer == null) return;
        videoPlayer.pause();
        pauseSounds();
        ArrayList<MediaItem> items = new ArrayList<>();
        for (Clip c : clips) items.add(videoItem(c));
        videoPlayer.setMediaItems(items);
        if (!items.isEmpty()) {
            videoPlayer.prepare();
            seekTimeline(where);
        }
        playButton.setText("▶");
    }

    private MediaItem videoItem(Clip clip) {
        return new MediaItem.Builder().setUri(clip.uri)
            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.fromMs).setEndPositionMs(clip.toMs).build()).build();
    }

    private void seekTimeline(long ms) {
        if (clips.isEmpty()) return;
        ms = Math.max(0, Math.min(Math.max(0, totalMs() - 1), ms));
        long elapsed = 0;
        for (int i = 0; i < clips.size(); i++) {
            long end = elapsed + clips.get(i).length();
            if (ms < end || i == clips.size() - 1) {
                videoPlayer.seekTo(i, Math.max(0, ms - elapsed));
                lastPreviewMs = -1;
                updateTransitionPreview(ms);
                if (videoPlayer.isPlaying()) syncSounds(ms);
                else pauseSounds();
                return;
            }
            elapsed = end;
        }
    }

    private void togglePlayback() {
        if (exporting || clips.isEmpty()) return;
        if (videoPlayer.isPlaying()) {
            videoPlayer.pause();
            pauseSounds();
            playButton.setText("▶");
        } else {
            if (currentTimelineMs() >= Math.max(0, totalMs() - 300)) seekTimeline(0);
            if (catalogPlayer != null) catalogPlayer.pause();
            videoPlayer.play();
            playButton.setText("Ⅱ");
            syncSounds(currentTimelineMs());
        }
    }

    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            if (videoPlayer != null && !exporting) {
                long now = currentTimelineMs();
                if (!scrubbing && totalMs() > 0) playhead.setProgress((int)(1000 * now / totalMs()));
                if (!scrubbing) timeLabel.setText(formatMs(now) + " / " + formatMs(totalMs()));
                updateTransitionPreview(now);
                if (videoPlayer.isPlaying()) {
                    playButton.setText("Ⅱ");
                    syncSounds(now);
                } else {
                    playButton.setText("▶");
                    pauseSounds();
                }
                lastPreviewMs = now;
            }
            handler.postDelayed(this, 220);
        }
    };

    private void syncSounds(long now) {
        for (SoundTrack t : sounds) {
            long len = t.length();
            long elapsed = now - t.startMs;
            if (len <= 0 || elapsed < 0 || (!t.repeat && elapsed >= len) || t.volume <= 0) {
                if (t.player != null) t.player.pause();
                continue;
            }
            if (t.player == null) {
                t.player = new ExoPlayer.Builder(this).build();
                t.player.setMediaItem(MediaItem.fromUri(t.uri));
                t.player.prepare();
            }
            long sourcePosition = t.inMs + (t.repeat ? (elapsed % len) : elapsed);
            long current = t.player.getCurrentPosition();
            if (!t.player.isPlaying() || Math.abs(current - sourcePosition) > 450 || now < lastPreviewMs) {
                t.player.seekTo(sourcePosition);
            }
            t.player.setVolume(t.volume / 100f);
            t.player.play();
        }
    }

    private void pauseSounds() { for (SoundTrack t : sounds) if (t.player != null) t.player.pause(); }
    private void releaseSound(SoundTrack t) { if (t.player != null) { t.player.release(); t.player = null; } }

    private void redraw() {
        renderVideoTimeline();
        renderSoundTracks();
        originalSeek.setProgress(originalVolume);
        originalLabel.setText("ÁUDIO ORIGINAL DOS VÍDEOS  •  " + originalVolume + "%");
        videoPlayer.setVolume(originalVolume / 100f);
    }

    private void renderVideoTimeline() {
        clipTimeline.removeAllViews();
        clipActions.removeAllViews();
        summaryLabel.setText("VÍDEO  •  " + clips.size() + " clipes  •  " + formatMs(totalMs()));
        if (clips.isEmpty()) {
            clipTimeline.addView(text("Adicione vídeos do Flow para montar sua história.", 13, MUTED, false));
            clipLabel.setText("A sequência e os cortes aparecem aqui.");
            return;
        }
        if (selectedClip < 0 || selectedClip >= clips.size()) selectedClip = 0;
        for (int i = 0; i < clips.size(); i++) {
            final int index = i;
            Clip c = clips.get(i);
            LinearLayout tile = vertical();
            int width = Math.max(86, Math.min(165, (int)(c.length() / 1000.0 * 13)));
            tile.setBackground(outline(index == selectedClip ? CYAN : DARK, index == selectedClip ? 2 : 1, 8));
            tile.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(width), dp(118));
            layout.setMargins(0, 0, dp(5), 0);
            clipTimeline.addView(tile, layout);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(Color.rgb(45, 46, 56));
            tile.addView(thumb, new LinearLayout.LayoutParams(-1, dp(76)));
            makeThumbnail(c, thumb);
            TextView name = text((i + 1) + " · " + formatMs(c.length()), 12, WHITE, true);
            name.setGravity(Gravity.CENTER);
            tile.addView(name, margin(0, 6));
            tile.setOnClickListener(v -> { selectedClip = index; seekStartOf(index); renderVideoTimeline(); });
            if (index < clips.size() - 1) {
                TextView transitionChip = text(transitionSymbol(c.transition), 11, CYAN, true);
                transitionChip.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(dp(58), dp(110));
                chip.setMargins(0, 0, dp(4), 0);
                clipTimeline.addView(transitionChip, chip);
                transitionChip.setOnClickListener(v -> chooseTransition(index));
            }
        }
        Clip active = clips.get(selectedClip);
        clipLabel.setText("Selecionado: " + (selectedClip + 1) + ". " + active.name + "  •  " + formatMs(active.length()));
        LinearLayout row = horizontal();
        clipActions.addView(row);
        equalButton(row, "✂ CORTAR", PURPLE, () -> editClip(active));
        equalButton(row, "◀ MOVER", DARK, () -> moveClip(-1));
        equalButton(row, "MOVER ▶", DARK, () -> moveClip(1));
        LinearLayout row2 = horizontal();
        clipActions.addView(row2, margin(0, 6));
        equalButton(row2, "DUPLICAR", DARK, () -> duplicateClip());
        equalButton(row2, "REMOVER", DARK, () -> removeClip());
        if (selectedClip < clips.size() - 1) {
            LinearLayout row3 = horizontal();
            clipActions.addView(row3, margin(0, 6));
            equalButton(row3, "✦ TRANSIÇÃO: " + active.transition.toUpperCase(Locale.ROOT), GREEN,
                () -> chooseTransition(selectedClip));
        }
    }

    private void makeThumbnail(Clip c, ImageView target) {
        worker.execute(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            Bitmap thumb = null;
            try {
                mmr.setDataSource(this, c.uri);
                thumb = mmr.getFrameAtTime((c.fromMs + Math.min(350, c.length() / 2)) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (thumb != null) thumb = Bitmap.createScaledBitmap(thumb, 160, 100, true);
            } catch (Exception ignored) { }
            finally { try { mmr.release(); } catch (Exception ignored) { } }
            final Bitmap bitmap = thumb;
            if (bitmap != null) runOnUiThread(() -> target.setImageBitmap(bitmap));
        });
    }

    private void seekStartOf(int index) {
        long start = 0;
        for (int i = 0; i < index; i++) start += clips.get(i).length();
        seekTimeline(start);
    }
    private void moveClip(int delta) {
        if (exporting || selectedClip < 0) return;
        int next = selectedClip + delta;
        if (next < 0 || next >= clips.size()) return;
        Clip c = clips.remove(selectedClip);
        clips.add(next, c);
        selectedClip = next;
        reloadVideo(0); redraw(); saveDraft();
    }
    private void removeClip() {
        if (exporting || selectedClip < 0 || clips.isEmpty()) return;
        clips.remove(selectedClip);
        selectedClip = Math.min(selectedClip, clips.size() - 1);
        reloadVideo(0); redraw(); saveDraft();
    }
    private void duplicateClip() {
        if (exporting || selectedClip < 0) return;
        Clip a = clips.get(selectedClip);
        Clip b = new Clip(a.uri, a.name, a.durationMs);
        b.fromMs = a.fromMs; b.toMs = a.toMs; b.transition = a.transition;
        clips.add(++selectedClip, b);
        reloadVideo(0); redraw(); saveDraft();
    }
    private void editClip(Clip c) {
        if (exporting) return;
        LinearLayout form = dialogForm();
        form.addView(text("Duração de origem: " + formatMs(c.durationMs) + " • início e fim em segundos", 13, Color.DKGRAY, false));
        EditText from = decimalInput("Início (segundos)", c.fromMs); form.addView(from);
        EditText to = decimalInput("Fim (segundos)", c.toMs); form.addView(to);
        new AlertDialog.Builder(this).setTitle("Cortar clipe")
            .setView(form).setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar", (d, i) -> {
                try {
                    long f = parseSeconds(from), t = parseSeconds(to);
                    if (f < 0 || t > c.durationMs || t - f < 250) throw new IllegalArgumentException();
                    c.fromMs = f; c.toMs = t;
                    reloadVideo(0); redraw(); saveDraft();
                } catch (Exception ex) { toast("Intervalo inválido. Confira início e fim."); }
            }).show();
    }

    private void renderSoundTracks() {
        soundTimeline.removeAllViews();
        soundActions.removeAllViews();
        soundsLabel.setText(sounds.isEmpty() ? "Adicione uma ou mais músicas, narrações ou efeitos." :
            sounds.size() + " faixas independentes  •  toque em uma faixa para ajustar");
        final int pxPerSecond = 8;
        int totalWidth = Math.max(dp(310), Math.min(dp(1300), (int)(totalMs() / 1000.0 * dp(pxPerSecond))));
        for (int i = 0; i < sounds.size(); i++) {
            final int index = i;
            SoundTrack t = sounds.get(i);
            LinearLayout lane = horizontal();
            lane.setGravity(Gravity.CENTER_VERTICAL);
            lane.setBackground(rounded(CARD, 8));
            LinearLayout.LayoutParams laneP = new LinearLayout.LayoutParams(totalWidth, dp(54));
            laneP.setMargins(0, 0, 0, dp(7));
            soundTimeline.addView(lane, laneP);
            int before = totalMs() == 0 ? 0 : (int)((double)t.startMs / totalMs() * totalWidth);
            int visible = Math.max(dp(67), (int)((double)Math.min(Math.max(1, totalMs()-t.startMs), t.repeat ? totalMs() - t.startMs : t.length()) / Math.max(1, totalMs()) * totalWidth));
            View gap = new View(this);
            lane.addView(gap, new LinearLayout.LayoutParams(Math.min(before, totalWidth - dp(65)), 1));
            TextView bar = text("♪  " + (index + 1) + ". " + t.name + "\n" + formatMs(t.startMs) + "   " + t.volume + "%" + (t.repeat ? "  ↻" : ""), 12, Color.BLACK, true);
            bar.setMaxLines(2);
            bar.setPadding(dp(9), dp(7), dp(9), dp(7));
            bar.setBackground(rounded(index % 2 == 0 ? PURPLE : GREEN, 7));
            lane.addView(bar, new LinearLayout.LayoutParams(Math.max(dp(64), Math.min(visible, totalWidth - Math.min(before, totalWidth-dp(65)))), dp(46)));
            bar.setOnClickListener(v -> editSound(t));
        }
        for (int i = 0; i < sounds.size(); i++) {
            final SoundTrack t = sounds.get(i);
            LinearLayout row = horizontal();
            soundActions.addView(row, margin(0, i == 0 ? 0 : 7));
            Button btn = button("♪ " + (i + 1) + ". " + shorten(t.name, 19) + "  •  " + t.volume + "%", DARK);
            btn.setTextColor(WHITE);
            btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            row.addView(btn, new LinearLayout.LayoutParams(0, dp(44), 1));
            btn.setOnClickListener(v -> editSound(t));
            Button delete = button("✕", DARK);
            delete.setTextColor(WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(44));
            lp.setMargins(dp(7), 0, 0, 0);
            row.addView(delete, lp);
            delete.setOnClickListener(v -> {
                if (exporting) return;
                releaseSound(t); sounds.remove(t); renderSoundTracks(); saveDraft();
            });
        }
    }

    private void editSound(SoundTrack t) {
        if (exporting) return;
        LinearLayout form = dialogForm();
        form.addView(text("Início da música na montagem (segundos)", 13, Color.DKGRAY, true));
        EditText start = decimalInput("Posição na linha do tempo", t.startMs);
        form.addView(start);
        form.addView(text("Trecho da música: início / fim em segundos", 13, Color.DKGRAY, true), margin(0, 12));
        EditText from = decimalInput("Início do áudio", t.inMs); form.addView(from);
        EditText to = decimalInput("Fim do áudio", t.outMs); form.addView(to);
        TextView volumeText = text("Volume: " + t.volume + "%", 14, Color.DKGRAY, true);
        form.addView(volumeText, margin(0, 10));
        SeekBar volume = new SeekBar(this);
        volume.setMax(100); volume.setProgress(t.volume);
        form.addView(volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { volumeText.setText("Volume: " + p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        CheckBox repeat = new CheckBox(this);
        repeat.setText("Repetir até o fim do vídeo");
        repeat.setChecked(t.repeat);
        repeat.setTextColor(Color.DKGRAY);
        form.addView(repeat);
        ScrollView wrap = new ScrollView(this);
        wrap.addView(form);
        new AlertDialog.Builder(this).setTitle("Editar trilha: " + shorten(t.name, 22))
            .setView(wrap).setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar", (d, which) -> {
                try {
                    long s = parseSeconds(start), f = parseSeconds(from), e = parseSeconds(to);
                    if (s < 0 || s >= totalMs() || f < 0 || e > t.durationMs || e - f < 200) throw new IllegalArgumentException();
                    t.startMs = s; t.inMs = f; t.outMs = e;
                    t.volume = volume.getProgress(); t.repeat = repeat.isChecked();
                    releaseSound(t); renderSoundTracks(); saveDraft();
                } catch (Exception error) { toast("Tempos inválidos. Verifique duração e posição."); }
            }).show();
    }

    // Alteracoes visuais renderizadas no video exportado; corte seco, dip to black,
    // dip to white e clarão ciano. Crossfade real ainda nao e suportado pelo Media3.
    private static RgbMatrix makeTransitionEffect(String incoming, String outgoing, long lengthMs) {
        return (presentationTimeUs, useHdr) -> {
            long ms = presentationTimeUs / 1000L;
            float amount = 0f;
            String effect = TRANSITIONS[0];
            if (!TRANSITIONS[0].equals(incoming) && ms >= 0 && ms < TRANSITION_MS) {
                amount = Math.max(0f, 1f - (float)ms / TRANSITION_MS);
                effect = incoming;
            }
            if (!TRANSITIONS[0].equals(outgoing) && ms > lengthMs - TRANSITION_MS && ms <= lengthMs + 20) {
                float next = Math.min(1f, (ms - Math.max(0, lengthMs - TRANSITION_MS)) / (float)Math.max(1, Math.min(TRANSITION_MS, lengthMs)));
                if (next > amount) { amount = next; effect = outgoing; }
            }
            amount = Math.min(1f, Math.max(0f, amount));
            float scale = 1f - amount, r=0f, g=0f, b=0f;
            if (TRANSITIONS[2].equals(effect)) { r=amount; g=amount; b=amount; }
            else if (TRANSITIONS[3].equals(effect)) { r=.04f*amount; g=.85f*amount; b=.74f*amount; }
            return new float[] {scale,0,0,0, 0,scale,0,0, 0,0,scale,0, r,g,b,1};
        };
    }

    private void chooseTransition(int position) {
        if (exporting || position < 0 || position >= clips.size() - 1) return;
        Clip clip = clips.get(position);
        new AlertDialog.Builder(this).setTitle("Troca da cena " + (position+1) + " → " + (position+2))
            .setSingleChoiceItems(TRANSITIONS, Arrays.asList(TRANSITIONS).indexOf(clip.transition),
                (dialog, which) -> {
                    clip.transition = TRANSITIONS[which];
                    renderVideoTimeline(); saveDraft(); dialog.dismiss();
                }).setNegativeButton("Cancelar", null).show();
    }
    private String transitionSymbol(String type) {
        if (TRANSITIONS[1].equals(type)) return "◀ PRETO ▶";
        if (TRANSITIONS[2].equals(type)) return "✦ FLASH ✦";
        if (TRANSITIONS[3].equals(type)) return "✧ NEON ✧";
        return "✂ CORTE";
    }
    private void updateTransitionPreview(long globalMs) {
        if (transitionMask == null || clips.isEmpty()) return;
        long elapsed = 0;
        float opacity = 0f;
        int color = Color.BLACK;
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            long start = elapsed, end = elapsed + clip.length();
            if (globalMs >= start && (globalMs < end || i == clips.size()-1)) {
                long local = globalMs - start;
                if (i > 0 && !TRANSITIONS[0].equals(clips.get(i-1).transition) && local < TRANSITION_MS) {
                    opacity = 1f - local/(float)TRANSITION_MS;
                    color = transitionColor(clips.get(i-1).transition);
                }
                if (i < clips.size()-1 && !TRANSITIONS[0].equals(clip.transition) && end-globalMs < TRANSITION_MS) {
                    float out = 1f - (end-globalMs)/(float)TRANSITION_MS;
                    if (out > opacity) { opacity = out; color = transitionColor(clip.transition); }
                }
                break;
            }
            elapsed = end;
        }
        transitionMask.setBackgroundColor(color);
        transitionMask.setAlpha(Math.min(1f, Math.max(0f, opacity)));
    }
    private int transitionColor(String type) {
        if (TRANSITIONS[2].equals(type)) return Color.WHITE;
        if (TRANSITIONS[3].equals(type)) return CYAN;
        return Color.BLACK;
    }

    // Trilhas originais desenvolvidas para o editor; a biblioteca funciona offline
    // e consulta um catalogo GitHub somente por acao explicita do usuario.
    private void openLibrary(boolean online) {
        if (exporting) return;
        if (!online) {
            ArrayList<AudioEntry> entries = new ArrayList<>();
            for (int i = 0; i < BUILTIN_FILES.length; i++)
                entries.add(new AudioEntry(BUILTIN_NAMES[i], BUILTIN_FILES[i], BUILTIN_GROUPS[i], false));
            showAudioEntries(entries, "♪ Áudios incluídos no app");
            return;
        }
        if (catalogLoading) { toast("Carregando músicas online..."); return; }
        catalogLoading = true;
        onlineStatus.setText("☁ Conectando ao catálogo...");
        worker.execute(() -> {
            try {
                String json = httpText(ONLINE_URL, 256 * 1024);
                JSONArray rows = new JSONObject(json).getJSONArray("tracks");
                ArrayList<AudioEntry> entries = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    String slug = row.getString("slug");
                    if (!slug.matches("[a-z0-9_-]{2,30}")) continue;
                    entries.add(new AudioEntry(row.getString("title"), slug,
                        row.optString("category", "Instrumental"), true));
                }
                runOnUiThread(() -> {
                    catalogLoading = false;
                    onlineStatus.setText("☁ Conectado • faixas adicionais disponíveis");
                    showAudioEntries(entries, "☁ Biblioteca online");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    catalogLoading = false;
                    onlineStatus.setText("Sem conexão com o catálogo • use os áudios incluídos");
                    new AlertDialog.Builder(this).setTitle("Biblioteca indisponível")
                        .setMessage("Confira sua conexão e tente novamente. Os áudios que já vêm no aplicativo continuam funcionando.\n" + friendly(ex))
                        .setPositiveButton("Entendi", null).show();
                });
            }
        });
    }
    private void showAudioEntries(ArrayList<AudioEntry> entries, String title) {
        if (entries.isEmpty()) { toast("Ainda não há sons neste catálogo."); return; }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            AudioEntry entry = entries.get(i);
            labels[i] = "♫  " + entry.title + "   •   " + entry.group + (entry.online ? "  ☁" : "  ✓");
        }
        new AlertDialog.Builder(this).setTitle(title)
            .setItems(labels, (d, i) -> showEntryActions(entries.get(i)))
            .setNegativeButton("Fechar", null).show();
    }
    private void showEntryActions(AudioEntry entry) {
        new AlertDialog.Builder(this).setTitle(entry.title)
            .setMessage(entry.group + (entry.online ? " • música original disponível online. O download fica salvo no aparelho." : " • incluído no aplicativo."))
            .setNeutralButton("▶ Ouvir", (d, w) -> acquireAudio(entry, file -> {
                if (catalogPlayer != null) { catalogPlayer.release(); catalogPlayer = null; }
                catalogPlayer = new ExoPlayer.Builder(this).build();
                catalogPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                catalogPlayer.prepare(); catalogPlayer.setVolume(.85f); catalogPlayer.play();
                toast("Ouvindo: " + entry.title);
            }))
            .setPositiveButton("+ Adicionar", (d, w) -> acquireAudio(entry, file -> {
                if (clips.isEmpty()) { toast("Adicione um vídeo antes de inserir a trilha."); return; }
                if (sounds.size() >= 6) { toast("Máximo de 6 faixas por montagem."); return; }
                long duration = duration(Uri.fromFile(file));
                if (duration < 200) { toast("Áudio inválido."); return; }
                long timeline = Math.min(currentTimelineMs(), Math.max(0, totalMs()-1));
                SoundTrack t = new SoundTrack(Uri.fromFile(file), entry.title, duration, timeline);
                t.repeat = !entry.group.equals("Efeito sonoro");
                sounds.add(t);
                renderSoundTracks(); saveDraft();
                toast("Trilha adicionada: " + entry.title);
            }))
            .setNegativeButton("Voltar", (d, w) -> openLibrary(entry.online)).show();
    }
    private void acquireAudio(AudioEntry entry, Consumer<File> callback) {
        toast(entry.online ? "Baixando som (primeira utilização)..." : "Preparando áudio...");
        worker.execute(() -> {
            File folder = new File(getFilesDir(), "library_audio");
            if (!folder.isDirectory()) folder.mkdirs();
            File file = new File(folder, entry.slug + ".m4a");
            try {
                if (!file.isFile() || file.length() < 2000) {
                    File part = new File(folder, entry.slug + ".partial");
                    try {
                        if (entry.online) {
                            byte[] audio = httpBinary(ONLINE_BASE + entry.slug + ".m4a", 1024*1024);
                            if (audio.length < 2000) throw new IOException("Áudio de tamanho inválido");
                            try (OutputStream out = new java.io.FileOutputStream(part)) { out.write(audio); }
                        } else {
                            try (InputStream in = getAssets().open("audio/" + entry.slug + ".m4a");
                                 OutputStream out = new java.io.FileOutputStream(part)) {
                                byte[] buf = new byte[16384]; int n;
                                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            }
                        }
                        if (!part.renameTo(file)) throw new IOException("Não foi possível salvar a música");
                    } finally { part.delete(); }
                }
                runOnUiThread(() -> callback.accept(file));
            } catch (Exception ex) { runOnUiThread(() -> toast("Falha ao obter áudio: " + friendly(ex))); }
        });
    }
    private static String httpText(String url, int maxBytes) throws IOException {
        return new String(httpBinary(url, maxBytes), StandardCharsets.UTF_8);
    }
    private static byte[] httpBinary(String url, int maxBytes) throws IOException {
        URL requested = new URL(url);
        if (!"https".equals(requested.getProtocol()) ||
             !("raw.githubusercontent.com".equals(requested.getHost()) || "github.com".equals(requested.getHost())))
            throw new IOException("Origem não permitida");
        HttpURLConnection connection = (HttpURLConnection)requested.openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "XandyCut/1.2");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Resposta HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = input.read(buf)) != -1) {
                    if (output.size()+n > maxBytes) throw new IOException("Catálogo acima do limite");
                    output.write(buf, 0, n);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    private List<AudioProcessor> volumeEffects(int percent) {
        ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor();
        for (int channels = 1; channels <= 6; channels++) {
            mix.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2).scaleBy(percent / 100f));
        }
        return Arrays.asList(new ToInt16PcmAudioProcessor(), mix);
    }

    private void export() {
        if (exporting) return;
        if (clips.isEmpty()) { toast("Escolha vídeos primeiro."); return; }
        long duration = totalMs();
        if (duration <= 0) { toast("Duração total inválida."); return; }
        videoPlayer.pause(); pauseSounds();
        ArrayList<EditedMediaItem> videoItems = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            EditedMediaItem.Builder b = new EditedMediaItem.Builder(videoItem(c));
            ArrayList<androidx.media3.common.Effect> videoEffects = new ArrayList<>();
            String incoming = i > 0 ? clips.get(i-1).transition : TRANSITIONS[0];
            String outgoing = i < clips.size()-1 ? c.transition : TRANSITIONS[0];
            if (!TRANSITIONS[0].equals(incoming) || !TRANSITIONS[0].equals(outgoing))
                videoEffects.add(makeTransitionEffect(incoming, outgoing, c.length()));
            b.setEffects(new Effects(originalVolume > 0 ? volumeEffects(originalVolume) :
                Collections.emptyList(), videoEffects));
            videoItems.add(b.build());
        }
        ArrayList<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(originalVolume > 0
            ? EditedMediaItemSequence.withAudioAndVideoFrom(videoItems)
            : EditedMediaItemSequence.withVideoFrom(videoItems));
        for (SoundTrack s : sounds) {
            if (s.volume == 0 || s.startMs >= duration || s.length() <= 0) continue;
            EditedMediaItemSequence.Builder lane = new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_AUDIO));
            if (s.startMs > 0) lane.addGap(s.startMs * 1000L);
            long remaining = duration - s.startMs;
            int n = 0;
            do {
                long take = Math.min(remaining, s.length());
                if (take < 100) break;
                MediaItem source = new MediaItem.Builder().setUri(s.uri)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(s.inMs).setEndPositionMs(s.inMs + take).build()).build();
                EditedMediaItem e = new EditedMediaItem.Builder(source)
                    .setEffects(new Effects(volumeEffects(s.volume), Collections.emptyList())).build();
                lane.addItem(e);
                remaining -= take;
                n++;
            } while (s.repeat && remaining >= 100 && n < 200);
            sequences.add(lane.build());
        }
        Composition composition = new Composition.Builder(sequences).build();
        tempFile = new File(getCacheDir(), "xandy_export_" + System.currentTimeMillis() + ".mp4");
        setExporting(true);
        statusLabel.setText("Renderizando vídeo com as trilhas... mantenha o aplicativo aberto.");
        progressBar.setProgress(0);
        try {
            transformer = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition c, ExportResult result) {
                        transformer = null;
                        statusLabel.setText("MP4 pronto. Salvando na galeria...");
                        saveExport(tempFile);
                    }
                    @Override public void onError(Composition c, ExportResult result, ExportException e) {
                        transformer = null; setExporting(false); cleanTemp();
                        statusLabel.setText("Não foi possível exportar: " + friendly(e));
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Falha na exportação")
                            .setMessage("Tente com vídeo MP4 e áudio MP3/M4A. Detalhe:\n" + friendly(e))
                            .setPositiveButton("Entendi", null).show();
                    }
                }).build();
            transformer.start(composition, tempFile.getAbsolutePath());
            handler.post(progressTick);
        } catch (Exception e) {
            transformer = null; setExporting(false); cleanTemp();
            statusLabel.setText("Erro ao iniciar exportação: " + friendly(e));
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!exporting || transformer == null) return;
            try {
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progressBar.setProgress(progressHolder.progress);
                    statusLabel.setText("Exportando: " + progressHolder.progress + "%");
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 500);
        }
    };

    private void saveExport(File file) {
        worker.execute(() -> {
            try {
                Uri uri = saveToGallery(file);
                runOnUiThread(() -> {
                    lastVideo = uri;
                    shareButton.setEnabled(true);
                    progressBar.setProgress(100);
                    setExporting(false);
                    statusLabel.setText("Concluído! Salvo na galeria, em Filmes/XandyCut.");
                    toast("Vídeo salvo na galeria.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setExporting(false);
                    statusLabel.setText("Não foi possível salvar: " + friendly(e));
                });
            } finally { if (file != null) file.delete(); }
        });
    }

    private Uri saveToGallery(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0) throw new IOException("Arquivo vazio");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "XandyCut_" + stamp + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XandyCut");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri target = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new IOException("Sem acesso à galeria");
        try (FileInputStream in = new FileInputStream(file);
             OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) throw new IOException("Não foi possível gravar o vídeo");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (Exception e) {
            getContentResolver().delete(target, null, null);
            throw new IOException(e);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0);
        getContentResolver().update(target, done, null, null);
        return target;
    }

    private void cancelExport() {
        if (!exporting || transformer == null) return;
        transformer.cancel(); transformer = null; setExporting(false); cleanTemp();
        statusLabel.setText("Exportação cancelada");
    }
    private void cleanTemp() { if (tempFile != null) tempFile.delete(); tempFile = null; }
    private void setExporting(boolean state) {
        exporting = state;
        exportButton.setEnabled(!state);
        progressBar.setVisibility(state ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(state ? View.VISIBLE : View.GONE);
    }
    private void share() {
        if (lastVideo == null) return;
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("video/mp4");
        it.putExtra(Intent.EXTRA_STREAM, lastVideo);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(it, "Compartilhar vídeo"));
    }

    private void saveDraft() {
        try {
            JSONObject root = new JSONObject();
            root.put("volume", originalVolume);
            JSONArray vc = new JSONArray();
            for (Clip c : clips) {
                JSONObject o = new JSONObject();
                o.put("uri", c.uri.toString()); o.put("name", c.name);
                o.put("duration", c.durationMs); o.put("from", c.fromMs); o.put("to", c.toMs);
                o.put("transition", c.transition);
                vc.put(o);
            }
            root.put("clips", vc);
            JSONArray va = new JSONArray();
            for (SoundTrack t : sounds) {
                JSONObject o = new JSONObject();
                o.put("uri", t.uri.toString()); o.put("name", t.name);
                o.put("duration", t.durationMs); o.put("from", t.inMs); o.put("to", t.outMs);
                o.put("start", t.startMs); o.put("volume", t.volume); o.put("repeat", t.repeat);
                va.put(o);
            }
            root.put("sounds", va);
            getPreferences(MODE_PRIVATE).edit().putString("draft_v1_2", root.toString()).apply();
        } catch (Exception ignored) { }
    }
    private void restoreDraft() {
        String data = getPreferences(MODE_PRIVATE).getString("draft_v1_2", null);
        if (data == null) return;
        try {
            JSONObject root = new JSONObject(data);
            originalVolume = root.optInt("volume", 55);
            JSONArray vc = root.optJSONArray("clips");
            if (vc != null) for (int i = 0; i < vc.length(); i++) {
                JSONObject o = vc.getJSONObject(i);
                Uri uri = Uri.parse(o.getString("uri"));
                Clip c = new Clip(uri, o.getString("name"), o.getLong("duration"));
                c.fromMs = o.getLong("from"); c.toMs = o.getLong("to");
                c.transition = o.optString("transition", TRANSITIONS[0]);
                clips.add(c);
            }
            JSONArray va = root.optJSONArray("sounds");
            if (va != null) for (int i = 0; i < va.length(); i++) {
                JSONObject o = va.getJSONObject(i);
                Uri uri = Uri.parse(o.getString("uri"));
                SoundTrack t = new SoundTrack(uri, o.getString("name"), o.getLong("duration"), o.getLong("start"));
                t.inMs = o.getLong("from"); t.outMs = o.getLong("to");
                t.volume = o.getInt("volume"); t.repeat = o.getBoolean("repeat");
                sounds.add(t);
            }
            if (!clips.isEmpty()) { selectedClip = 0; reloadVideo(0); }
        } catch (Exception ignored) { clips.clear(); sounds.clear(); }
    }

    private static String friendly(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return e.getClass().getSimpleName();
        return msg.length() > 210 ? msg.substring(0, 210) : msg;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout dialogForm() {
        LinearLayout l = vertical();
        l.setPadding(dp(20), dp(8), dp(20), dp(8));
        return l;
    }
    private EditText decimalInput(String hint, long milliseconds) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.2f", milliseconds / 1000.0));
        return e;
    }
    private static long parseSeconds(EditText e) {
        return Math.round(Double.parseDouble(e.getText().toString().replace(',', '.')) * 1000);
    }
    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(s); b.setTextColor(Color.BLACK);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextSize(12);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setMinHeight(dp(44)); b.setBackground(rounded(color, 9));
        return b;
    }
    private void equalButton(LinearLayout parent, String s, int color, Runnable callback) {
        Button b = button(s, color);
        if (color == DARK) b.setTextColor(WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(47), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(b, p);
        b.setOnClickListener(v -> callback.run());
    }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }
    private GradientDrawable outline(int color, int stroke, int radius) {
        GradientDrawable g = rounded(CARD, radius);
        g.setStroke(dp(stroke), color); return g;
    }
    private LinearLayout.LayoutParams margin(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(left), dp(top), 0, 0); return p;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private static String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
    private static String formatMs(long ms) {
        long seconds = Math.max(0, ms / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    @Override protected void onStop() {
        if (!exporting) { videoPlayer.pause(); pauseSounds(); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(previewTick);
        handler.removeCallbacks(progressTick);
        if (transformer != null) transformer.cancel();
        for (SoundTrack t : sounds) releaseSound(t);
        if (catalogPlayer != null) catalogPlayer.release();
        if (videoPlayer != null) videoPlayer.release();
        worker.shutdown();
        super.onDestroy();
    }
}