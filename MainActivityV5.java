package art.xandy.cut;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaExtractor;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
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
import android.view.MotionEvent;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.media3.effect.Presentation;
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
import java.util.LinkedHashSet;
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
    private static final int MAX_SOUND_TRACKS = 24;
    private static final int TIMELINE_DP_PER_SECOND = 21;
    private static final String ONLINE_URL =
        "https://raw.githubusercontent.com/alextelles0023-beep/XandyCut/main/audio/catalog.json";
    private static final String ONLINE_BASE =
        "https://github.com/alextelles0023-beep/XandyCut/releases/download/v1.3-beta/";
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
        long transitionDurationMs = TRANSITION_MS;
        int volume = 100;
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
        long durationMs, inMs, outMs, startMs, endMs, phaseMs = 0L;
        boolean effect = false;
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
            this.endMs = startMs + durationMs;
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
    private boolean cropVertical = false;
    private int originalVolume = 55;
    private long lastPreviewMs = -1;
    private int selectedClip = -1;

    private FrameLayout clipTimeline;
    private LinearLayout clipActions, soundTimeline, soundActions;
    private HorizontalScrollView soundScroll;
    private boolean syncingScroll = false;
    private TextView timeLabel, summaryLabel, statusLabel, soundsLabel, clipLabel;
    private SeekBar playhead, originalSeek;
    private Button playButton, exportButton, shareButton, cancelButton;
    private ProgressBar progressBar;
    private HorizontalScrollView stripScroll;
    private TextView originalLabel, onlineStatus;
    private View transitionMask;
    private ExoPlayer catalogPlayer;
    private boolean catalogLoading = false;
    private final HashMap<String,float[]> waveformPeaks = new HashMap<>();
    private final HashSet<String> waveformPending = new HashSet<>();

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
        root.addView(text("ESTÚDIO MULTIPISTA • V1.4 · 9:16", 11, MUTED, true), margin(0, 3));

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
        root.addView(text("VÍDEOS  •  Cenas sincronizadas às trilhas sonoras abaixo.", 11, MUTED, false), margin(0, 5));
        stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        root.addView(stripScroll, margin(0, 10));
        clipTimeline = new FrameLayout(this);
        stripScroll.addView(clipTimeline);
        clipLabel = text("", 12, MUTED, false);
        root.addView(clipLabel, margin(0, 10));
        clipActions = vertical();
        root.addView(clipActions, margin(0, 8));

        TextView h = text("TRILHAS DE ÁUDIO  •  MULTIPISTA", 16, WHITE, true);
        root.addView(h, margin(0, 22));
        soundsLabel = text("Arraste o áudio para movê-lo; use as extremidades para cortar. Toque para dividir ou ajustar.", 12, MUTED, false);
        root.addView(soundsLabel, margin(0, 7));
        soundScroll = new HorizontalScrollView(this);
        soundScroll.setHorizontalScrollBarEnabled(false);
        root.addView(soundScroll, margin(0, 10));
        soundTimeline = vertical();
        soundScroll.addView(soundTimeline);
        stripScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (syncingScroll) return;
            syncingScroll = true; soundScroll.scrollTo(x, 0); syncingScroll = false;
        });
        soundScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (syncingScroll) return;
            syncingScroll = true; stripScroll.scrollTo(x, 0); syncingScroll = false;
        });
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
                if (videoPlayer != null) updateVideoVolumePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { saveDraft(); }
        });

        root.addView(text("FORMATO DE SAÍDA · 1080 × 1920 VERTICAL (9:16)", 12, WHITE, true), margin(0, 19));
        CheckBox cropCheck = new CheckBox(this);
        cropCheck.setText("Preencher tela 9:16 (pode cortar bordas). Desmarcado: preservar tudo com bordas pretas.");
        cropCheck.setTextColor(MUTED); cropCheck.setTextSize(12); cropCheck.setChecked(cropVertical);
        cropCheck.setOnCheckedChangeListener((v, on) -> { cropVertical = on; saveDraft(); });
        root.addView(cropCheck, margin(0, 4));
        exportButton = button("EXPORTAR 1080 × 1920 • MP4", CYAN);
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
                if (sounds.size() >= MAX_SOUND_TRACKS) { toast("Limite de 24 faixas por montagem."); break; }
                SoundTrack track = new SoundTrack(uri, displayName(uri), duration, Math.min(insertion, Math.max(0,totalMs()-1)));
                track.endMs = totalMs();
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
                updateVideoVolumePreview();
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
            if (len <= 0 || elapsed < 0 || now >= t.endMs || (!t.repeat && elapsed+t.phaseMs >= len) || t.volume <= 0) {
                if (t.player != null) t.player.pause();
                continue;
            }
            if (t.player == null) {
                t.player = new ExoPlayer.Builder(this).build();
                t.player.setMediaItem(MediaItem.fromUri(t.uri));
                t.player.prepare();
            }
            long sourcePosition = t.inMs + (t.repeat ? ((Math.max(0,t.phaseMs) + elapsed) % len) : (t.phaseMs + elapsed));
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
        updateVideoVolumePreview();
    }

    private int msToPixels(long ms) {
        return (int)Math.min(200000L,Math.max(0L,Math.round(ms / 1000d * dp(TIMELINE_DP_PER_SECOND))));
    }
    private long pixelsToMs(float px) {
        return Math.round(px / Math.max(1f,dp(TIMELINE_DP_PER_SECOND))*1000d);
    }
    private int timelineWidth() {
        return Math.max(dp(340),msToPixels(totalMs())+dp(24));
    }
    private void renderVideoTimeline() {
        clipTimeline.removeAllViews();
        clipActions.removeAllViews();
        summaryLabel.setText("VÍDEO  •  " + clips.size() + " cenas  •  " + formatMs(totalMs()));
        int width=timelineWidth();
        clipTimeline.setLayoutParams(new FrameLayout.LayoutParams(width,dp(153)));
        if (clips.isEmpty()) {
            TextView placeholder=text("Toque em + VÍDEOS para começar a edição.",13,MUTED,false);
            clipTimeline.addView(placeholder,new FrameLayout.LayoutParams(-2,dp(60)));
            clipLabel.setText("O vídeo ficará acima das faixas de música.");
            return;
        }
        if (selectedClip < 0 || selectedClip >= clips.size()) selectedClip=0;
        int seconds=(int)Math.min(18000,totalMs()/1000+1);
        for(int second=0;second<seconds;second+=5) {
            TextView tick=text(formatMs(second*1000L),10,MUTED,false);
            tick.setGravity(Gravity.START);
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(62),dp(22));
            p.leftMargin=msToPixels(second*1000L);
            clipTimeline.addView(tick,p);
        }
        long offset=0L;
        for(int i=0;i<clips.size();i++) {
            final int index=i;Clip c=clips.get(i);
            int x=msToPixels(offset);
            int w=Math.max(dp(8),msToPixels(c.length()));
            LinearLayout tile=vertical();
            tile.setPadding(dp(2),dp(3),dp(2),dp(3));
            tile.setBackground(outline(index==selectedClip?CYAN:DARK,index==selectedClip?2:1,5));
            FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(w,dp(112));
            position.leftMargin=x;position.topMargin=dp(28);
            clipTimeline.addView(tile,position);
            ImageView thumb=new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(Color.rgb(45,46,56));
            tile.addView(thumb,new LinearLayout.LayoutParams(-1,dp(77)));
            makeThumbnail(c,thumb);
            TextView label=text((index+1)+"  "+formatMs(c.length())+"  ♪"+c.volume+"%",11,WHITE,true);
            label.setSingleLine(true);label.setGravity(Gravity.CENTER);
            tile.addView(label,margin(0,5));
            tile.setOnClickListener(v -> {selectedClip=index;seekStartOf(index);renderVideoTimeline();});
            if(i<clips.size()-1) {
                TextView transition=text("✦",17,CYAN,true);
                transition.setGravity(Gravity.CENTER);
                transition.setBackground(outline(CYAN,1,8));
                FrameLayout.LayoutParams chip=new FrameLayout.LayoutParams(dp(30),dp(30));
                chip.leftMargin=Math.max(0,x+w-dp(15));chip.topMargin=dp(61);
                clipTimeline.addView(transition,chip);
                transition.setOnClickListener(v -> chooseTransition(index));
            }
            offset+=c.length();
        }
        Clip active=clips.get(selectedClip);
        clipLabel.setText("Cena "+(selectedClip+1)+"  •  "+active.name+"  •  "+formatMs(active.length())+"  •  som "+active.volume+"%");
        LinearLayout row=horizontal();clipActions.addView(row);
        equalButton(row,"✂ CORTAR",PURPLE,()->editClip(active));
        equalButton(row,"◀ MOVER",DARK,()->moveClip(-1));
        equalButton(row,"MOVER ▶",DARK,()->moveClip(1));
        LinearLayout row2=horizontal();clipActions.addView(row2,margin(0,6));
        equalButton(row2,"DUPLICAR",DARK,()->duplicateClip());
        equalButton(row2,"REMOVER",DARK,()->removeClip());
        if(selectedClip<clips.size()-1) {
            LinearLayout row3=horizontal();clipActions.addView(row3,margin(0,6));
            equalButton(row3,"✦ TRANSIÇÃO: "+active.transition.toUpperCase(Locale.ROOT),GREEN,
                ()->chooseTransition(selectedClip));
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
    private void updateVideoVolumePreview() {
        if (videoPlayer == null) return;
        int index = videoPlayer.getCurrentMediaItemIndex();
        if (index < 0 || index >= clips.size()) index = selectedClip;
        int clipVol = index >= 0 && index < clips.size() ? clips.get(index).volume : 100;
        videoPlayer.setVolume((originalVolume / 100f) * (clipVol / 100f));
    }
    private void editClip(Clip c) {
        if (exporting) return;
        LinearLayout form = dialogForm();
        form.addView(text("Duração da cena: " + formatMs(c.durationMs) + " • corte em segundos", 13, Color.DKGRAY, false));
        EditText from = decimalInput("Início (segundos)", c.fromMs); form.addView(from);
        EditText to = decimalInput("Fim (segundos)", c.toMs); form.addView(to);
        form.addView(text("VOLUME DO SOM ORIGINAL DESTA CENA", 13, Color.DKGRAY, true), margin(0, 16));
        TextView volLabel = text("Cena: " + c.volume + "%  •  Volume geral: " + originalVolume + "%", 13, Color.DKGRAY, false);
        form.addView(volLabel);
        SeekBar clipVol = new SeekBar(this); clipVol.setMax(100); clipVol.setProgress(c.volume);
        clipVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb,int p,boolean u) { volLabel.setText("Cena: " + p + "%  •  Volume geral: " + originalVolume + "%"); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        form.addView(clipVol);
        form.addView(text("Use 0% para silenciar o clipe enquanto a música toca. A regulagem não muda os outros clipes.", 12, Color.DKGRAY, false));
        ScrollView scroll = new ScrollView(this);scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Editar cena " + (clips.indexOf(c)+1))
            .setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            try {
                long f = parseSeconds(from), t = parseSeconds(to);
                if (f<0 || t>c.durationMs || t-f<250) throw new IllegalArgumentException();
                c.fromMs=f; c.toMs=t; c.volume=clipVol.getProgress();
                reloadVideo(0); redraw(); saveDraft(); dialog.dismiss();
            } catch(Exception ex) { toast("Corte inválido. Confira os segundos de início e fim."); }
        }));
        dialog.show();
    }

        private static final int WAVEFORM_BINS=96;
    private class WaveformView extends View {
        final Uri audio;
        final int tint;
        final long fromMs, toMs, durationMs;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        WaveformView(Uri uri,int tint,long f,long t,long d) {
            super(MainActivity.this);
            audio=uri;this.tint=tint;fromMs=f;toMs=t;durationMs=d;
            ensureWaveform(uri);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float[] peaks=waveformPeaks.get(audio.toString());
            float width=getWidth(), height=getHeight(), center=height/2f;
            int barCount= WAVEFORM_BINS;
            if(peaks==null) {
                paint.setColor(Color.argb(105,255,255,255));
                canvas.drawLine(0,center,width,center,paint);
                return;
            }
            float step=width/barCount;
            float max=Math.max(1f,height*.49f);
            for(int i=0;i<barCount;i++) {
                float progress=(i+.5f)/barCount;
                boolean selected=progress>=fromMs/(float)Math.max(1L,durationMs)
                    && progress<=toMs/(float)Math.max(1L,durationMs);
                paint.setColor(selected ? tint : Color.argb(110,215,220,230));
                float barHeight=Math.max(dp(2),peaks[i]*max);
                float left=i*step+step*.19f, right=left+Math.max(1f,step*.58f);
                canvas.drawRoundRect(left,center-barHeight,right,center+barHeight,dp(1),dp(1),paint);
            }
        }
    }
    private void ensureWaveform(Uri uri) {
        String key=uri.toString();
        if (waveformPeaks.containsKey(key)||waveformPending.contains(key)) return;
        waveformPending.add(key);
        worker.execute(() -> {
            float[] peaks=decodeWaveform(uri);
            runOnUiThread(() -> {
                waveformPending.remove(key);
                waveformPeaks.put(key,peaks);
                if (!isFinishing()&&!exporting) renderSoundTracks();
            });
        });
    }
    private float[] decodeWaveform(Uri uri) {
        float[] peaks=new float[WAVEFORM_BINS];
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try {
            if ("file".equals(uri.getScheme())) extractor.setDataSource(uri.getPath());
            else extractor.setDataSource(this,uri,null);
            MediaFormat inputFormat=null;
            int audioTrack=-1;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if (mime!=null && mime.startsWith("audio/")) {inputFormat=f;audioTrack=i;break;}
            }
            if(audioTrack<0 || inputFormat==null) return peaks;
            extractor.selectTrack(audioTrack);
            long totalUs=inputFormat.containsKey(MediaFormat.KEY_DURATION)
                ? inputFormat.getLong(MediaFormat.KEY_DURATION)
                : Math.max(1000,duration(uri)*1000);
            String mime=inputFormat.getString(MediaFormat.KEY_MIME);
            if(mime==null)return peaks;
            codec=MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat,null,null,0);
            codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean endInput=false,endOutput=false;
            int loops=0, sampleRate=inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? Math.max(1,inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)):2;
            while(!endOutput&&loops++<18000 && !Thread.currentThread().isInterrupted()) {
                if(!endInput) {
                    int index=codec.dequeueInputBuffer(10000);
                    if(index>=0) {
                        ByteBuffer ib=codec.getInputBuffer(index);
                        if(ib==null)throw new IllegalStateException("Buffer de áudio ausente");
                        ib.clear();
                        int size=extractor.readSampleData(ib,0);
                        if(size<0) {
                            codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            endInput=true;
                        } else {
                            codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);
                            extractor.advance();
                        }
                    }
                }
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of=codec.getOutputFormat();
                    if(of.containsKey(MediaFormat.KEY_SAMPLE_RATE))sampleRate=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if(of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=Math.max(1,of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                } else if(index>=0) {
                    ByteBuffer ob=codec.getOutputBuffer(index);
                    if(ob!=null&&info.size>1) {
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int stride=Math.max(channels*2,(info.size/180/(channels*2))*(channels*2));
                        for(int p=0;p+1<info.size;p+=stride) {
                            int absolute=info.offset+p;
                            if(absolute+1>=ob.limit())break;
                            float timeUs=info.presentationTimeUs+
                                (p/(float)Math.max(1,channels*2*sampleRate))*1000000f;
                            int bin=(int)(timeUs/Math.max(1L,totalUs)*WAVEFORM_BINS);
                            if(bin<0||bin>=WAVEFORM_BINS)continue;
                            float sample=Math.abs(ob.getShort(absolute)/32768f);
                            peaks[bin]=Math.max(peaks[bin],sample);
                        }
                    }
                    endOutput=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(index,false);
                }
            }
        } catch(Exception e) {
            android.util.Log.w("XandyCut","Nao foi possivel extrair onda do audio: "+friendly(e));
        } finally {
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}
            try{extractor.release();}catch(Exception ignored){}
        }
        float strongest=0f;
        for(float v:peaks)strongest=Math.max(strongest,v);
        if(strongest>0f)for(int i=0;i<peaks.length;i++)peaks[i]=Math.min(1f,peaks[i]/strongest);
        return peaks;
    }

    private long snapSceneBoundary(long candidate) {
        if (clips.isEmpty()) return candidate;
        long elapsed=0;
        long best=candidate,closest=260;
        for(Clip c:clips) {
            long delta=Math.abs(candidate-elapsed);
            if(delta<closest){closest=delta;best=elapsed;}
            elapsed+=c.length();
        }
        long delta=Math.abs(candidate-elapsed);
        if(delta<closest) best=elapsed;
        return best;
    }
    private static long bound(long number,long min,long max) {
        return Math.max(min,Math.min(Math.max(min,max),number));
    }
    private View.OnTouchListener audioGesture(SoundTrack t,FrameLayout tile,TextView label,int mode) {
        return new View.OnTouchListener() {
            float downX;long originalStart,originalEnd,originalPhase;
            boolean moving=false;
            @Override public boolean onTouch(View view,MotionEvent event) {
                if(exporting) return true;
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN) {
                    downX=event.getRawX();
                    originalStart=t.startMs;originalEnd=t.endMs;originalPhase=t.phaseMs;
                    moving=false;
                    videoPlayer.pause();pauseSounds();
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if(action==MotionEvent.ACTION_MOVE) {
                    long change=pixelsToMs(event.getRawX()-downX);
                    if(Math.abs(change)>=60) moving=true;
                    if(!moving) return true;
                    long duration=originalEnd-originalStart;
                    if(mode==0) {
                        long shifted=snapSceneBoundary(originalStart+change);
                        t.startMs=bound(shifted,0,Math.max(0,totalMs()-duration));
                        t.endMs=t.startMs+duration;
                        t.phaseMs=originalPhase;
                    } else if(mode==1) {
                        long earliest=Math.max(0,originalStart-originalPhase);
                        long last=originalEnd-250;
                        if(!t.repeat)last=Math.min(last,originalStart+Math.max(0,t.length()-originalPhase-250));
                        long chosen=snapSceneBoundary(originalStart+change);
                        t.startMs=bound(chosen,earliest,last);
                        t.endMs=originalEnd;
                        t.phaseMs=originalPhase+(t.startMs-originalStart);
                    } else {
                        long latest=t.repeat?totalMs():
                            Math.min(totalMs(),originalStart+Math.max(250,t.length()-originalPhase));
                        long chosen=snapSceneBoundary(originalEnd+change);
                        t.endMs=bound(chosen,originalStart+250,latest);
                        t.startMs=originalStart;t.phaseMs=originalPhase;
                    }
                    FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)tile.getLayoutParams();
                    params.leftMargin=msToPixels(t.startMs);
                    params.width=Math.max(dp(12),msToPixels(t.endMs-t.startMs));
                    tile.setLayoutParams(params);
                    label.setText(shorten(t.name,20)+"   "+formatMs(t.startMs)+" – "+formatMs(t.endMs));
                    return true;
                }
                if(action==MotionEvent.ACTION_UP) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    if(moving) {
                        releaseSound(t);renderSoundTracks();saveDraft();
                    } else if(mode==0) editSound(t);
                    else editSound(t);
                    return true;
                }
                if(action==MotionEvent.ACTION_CANCEL) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    if(moving) {releaseSound(t);renderSoundTracks();saveDraft();}
                    return true;
                }
                return true;
            }
        };
    }
    private void splitTrackAtPlayhead(SoundTrack track) {
        if(exporting || clips.isEmpty()) return;
        long at=currentTimelineMs();
        if(at<=track.startMs+250 || at>=track.endMs-250) {
            toast("Posicione o cursor dentro do áudio e toque em DIVIDIR.");return;
        }
        if(sounds.size()>=MAX_SOUND_TRACKS) {toast("Limite de 24 segmentos de áudio.");return;}
        SoundTrack next=new SoundTrack(track.uri,track.name,track.durationMs,at);
        next.inMs=track.inMs;next.outMs=track.outMs;
        next.endMs=track.endMs;
        next.volume=track.volume;next.repeat=track.repeat;next.effect=track.effect;
        next.phaseMs=track.phaseMs+(at-track.startMs);
        if(next.repeat && next.length()>0) next.phaseMs %= next.length();
        track.endMs=at;
        releaseSound(track);
        sounds.add(sounds.indexOf(track)+1,next);
        renderSoundTracks();saveDraft();
        toast("Áudio dividido em "+formatMs(at)+". Edite cada parte separadamente.");
    }
    private void renderSoundTracks() {
        soundTimeline.removeAllViews();
        soundActions.removeAllViews();
        soundsLabel.setText(sounds.isEmpty()
            ? "Importe músicas ou escolha a biblioteca online. Os áudios aparecem em faixas abaixo do vídeo."
            : sounds.size()+" partes de áudio  •  mesma escala do vídeo acima  •  arraste │ bordas para cortar");
        int width=timelineWidth();
        if(sounds.isEmpty()) {
            TextView empty=text("♪  Toque em + ÁUDIO ou ☁ ONLINE para adicionar sua primeira faixa.",12,MUTED,false);
            soundTimeline.addView(empty,new LinearLayout.LayoutParams(width,dp(52)));
        }
        for(int i=0;i<sounds.size();i++) {
            final int index=i;SoundTrack t=sounds.get(i);
            FrameLayout lane=new FrameLayout(this);
            lane.setBackground(rounded(i%2==0?CARD:DARK,7));
            LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(width,dp(86));
            row.setMargins(0,0,0,dp(6));
            soundTimeline.addView(lane,row);
            long visibleLength=Math.max(250,t.endMs-t.startMs);
            int barWidth=Math.max(dp(12),msToPixels(visibleLength));
            int left=msToPixels(t.startMs);
            FrameLayout tile=new FrameLayout(this);
            tile.setBackground(rounded(index%2==0?PURPLE:GREEN,8));
            FrameLayout.LayoutParams pos=new FrameLayout.LayoutParams(barWidth,dp(72));
            pos.leftMargin=left;pos.topMargin=dp(7);
            lane.addView(tile,pos);
            LinearLayout content=vertical();
            content.setPadding(dp(25),dp(5),dp(25),dp(4));
            tile.addView(content,new FrameLayout.LayoutParams(-1,-1));
            TextView name=text("♪ "+(i+1)+". "+t.name+"  "+t.volume+"%  "+formatMs(t.startMs)+"–"+formatMs(t.endMs),11,Color.BLACK,true);
            name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.addView(name,new LinearLayout.LayoutParams(-1,dp(21)));
            WaveformView wave=new WaveformView(t.uri,Color.rgb(24,31,52),t.inMs,t.outMs,t.durationMs);
            content.addView(wave,new LinearLayout.LayoutParams(-1,dp(35)));
            content.setOnTouchListener(audioGesture(t,tile,name,0));
            TextView handleLeft=text("┃",22,Color.BLACK,true);
            handleLeft.setGravity(Gravity.CENTER);
            handleLeft.setBackground(rounded(Color.argb(90,255,255,255),5));
            FrameLayout.LayoutParams lh=new FrameLayout.LayoutParams(dp(25),-1,Gravity.START);
            tile.addView(handleLeft,lh);
            handleLeft.setOnTouchListener(audioGesture(t,tile,name,1));
            TextView handleRight=text("┃",22,Color.BLACK,true);
            handleRight.setGravity(Gravity.CENTER);
            handleRight.setBackground(rounded(Color.argb(90,255,255,255),5));
            FrameLayout.LayoutParams rh=new FrameLayout.LayoutParams(dp(25),-1,Gravity.END);
            tile.addView(handleRight,rh);
            handleRight.setOnTouchListener(audioGesture(t,tile,name,2));
        }
        for(int i=0;i<sounds.size();i++) {
            SoundTrack t=sounds.get(i);
            LinearLayout controls=horizontal();
            soundActions.addView(controls,margin(0,i==0?3:6));
            Button edit=button("♪ "+(i+1)+". "+shorten(t.name,12),DARK);
            edit.setTextColor(WHITE);
            controls.addView(edit,new LinearLayout.LayoutParams(0,dp(44),2));
            edit.setOnClickListener(v->editSound(t));
            Button split=button("✂ DIVIDIR",GREEN);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(44),1);
            sp.setMargins(dp(4),0,0,0);controls.addView(split,sp);
            split.setOnClickListener(v->splitTrackAtPlayhead(t));
            Button del=button("✕",DARK);del.setTextColor(WHITE);
            LinearLayout.LayoutParams xp=new LinearLayout.LayoutParams(dp(45),dp(44));xp.setMargins(dp(4),0,0,0);
            controls.addView(del,xp);
            del.setOnClickListener(v->{
                if(exporting)return;
                releaseSound(t);sounds.remove(t);renderSoundTracks();saveDraft();
            });
        }
    }

    private long startOfScene(int index) {
        long elapsed = 0;
        for (int i = 0; i < Math.min(index, clips.size()); i++) elapsed += clips.get(i).length();
        return elapsed;
    }
    private int sceneAt(long position) {
        if (clips.isEmpty()) return 0;
        long elapsed = 0;
        for (int i = 0; i < clips.size(); i++) {
            elapsed += clips.get(i).length();
            if (position < elapsed) return i;
        }
        return clips.size()-1;
    }
    private void editSound(SoundTrack t) {
        if (exporting || clips.isEmpty()) return;
        LinearLayout form = dialogForm();
        form.addView(text("ONDE A MÚSICA TOCA NA MONTAGEM", 14, Color.DKGRAY, true));
        form.addView(text("Escolha a primeira e a última cena. Você pode usar uma música nas cenas 1–3 e outra nas cenas 4–8, por exemplo.", 12, Color.DKGRAY, false), margin(0, 4));
        String[] labels = new String[clips.size()];
        for (int i = 0; i < clips.size(); i++)
            labels[i] = "Cena " + (i+1) + "  ·  " + formatMs(clips.get(i).length()) + "  ·  " + shorten(clips.get(i).name, 18);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        form.addView(text("Começar na cena", 13, Color.DKGRAY, true), margin(0, 12));
        Spinner sceneFrom = new Spinner(this); sceneFrom.setAdapter(adapter);
        int initialFrom = sceneAt(t.startMs); sceneFrom.setSelection(initialFrom);
        form.addView(sceneFrom);
        form.addView(text("Terminar na cena (inclusive)", 13, Color.DKGRAY, true), margin(0, 8));
        Spinner sceneTo = new Spinner(this); sceneTo.setAdapter(adapter);
        int initialTo = sceneAt(Math.max(t.startMs, t.endMs-1)); sceneTo.setSelection(initialTo);
        form.addView(sceneTo);
        TextView spanLabel = text("Abrange " + (initialTo-initialFrom+1) + " cena(s)", 12, Color.DKGRAY, false);
        form.addView(spanLabel, margin(0, 5));
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int count = sceneTo.getSelectedItemPosition() - sceneFrom.getSelectedItemPosition() + 1;
                spanLabel.setText(count>0 ? "Abrange " + count + " cena(s)" : "Escolha uma cena final igual ou posterior.");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        sceneFrom.setOnItemSelectedListener(listener); sceneTo.setOnItemSelectedListener(listener);
        form.addView(text("POSIÇÃO EXATA NAS CENAS (segundos)", 13, Color.DKGRAY, true), margin(0, 16));
        form.addView(text("0,00 = início da cena. Para terminar no fim dela, use sua duração mostrada acima.", 12, Color.DKGRAY, false));
        EditText startInScene = decimalInput("Segundo de entrada na primeira cena", Math.max(0,t.startMs-startOfScene(initialFrom)));
        form.addView(startInScene);
        EditText endInScene = decimalInput("Segundo de saída na última cena", Math.max(0,t.endMs-startOfScene(initialTo)));
        form.addView(endInScene);
        Button fullScenes = button("Usar as cenas inteiras", GREEN);
        form.addView(fullScenes, margin(0, 6));
        fullScenes.setOnClickListener(v -> {
            startInScene.setText("0.00");
            endInScene.setText(String.format(Locale.US,"%.2f",clips.get(sceneTo.getSelectedItemPosition()).length()/1000.0));
        });
        form.addView(text("CORTAR UM TRECHO DO ARQUIVO DE ÁUDIO", 13, Color.DKGRAY, true), margin(0, 16));
        form.addView(text("A música original dura " + formatMs(t.durationMs) + ". Escolha de qual segundo até qual segundo você quer ouvir.", 12, Color.DKGRAY, false));
        EditText from = decimalInput("Começo do áudio original", t.inMs); form.addView(from);
        EditText to = decimalInput("Fim do áudio original", t.outMs); form.addView(to);
        TextView volumeText = text("Volume: " + t.volume + "%", 14, Color.DKGRAY, true);
        form.addView(volumeText, margin(0, 12));
        SeekBar volume = new SeekBar(this); volume.setMax(100); volume.setProgress(t.volume); form.addView(volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean user) { volumeText.setText("Volume: " + p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        CheckBox repeat = new CheckBox(this);
        repeat.setText("Repetir o trecho até o fim das cenas escolhidas");
        repeat.setChecked(t.repeat); repeat.setTextColor(Color.DKGRAY);
        form.addView(repeat);
        ScrollView wrap = new ScrollView(this); wrap.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Editar trilha: " + shorten(t.name, 24))
            .setView(wrap).setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int first = sceneFrom.getSelectedItemPosition();
                int last = sceneTo.getSelectedItemPosition();
                if (first < 0 || last < first) throw new IllegalArgumentException("Selecione cenas em ordem.");
                long startOffset = parseSeconds(startInScene);
                long endOffset = parseSeconds(endInScene);
                if (startOffset < 0 || startOffset >= clips.get(first).length() ||
                    endOffset <= 0) throw new IllegalArgumentException("Confira os segundos dentro da cena.");
                // Limita valores herdados ao selecionar uma cena de duração diferente.
                endOffset = Math.min(endOffset, clips.get(last).length());
                long start = startOfScene(first) + startOffset;
                long end = startOfScene(last) + endOffset;
                long f = parseSeconds(from), e = parseSeconds(to);
                if (end-start < 250 || end > totalMs() || f < 0 || e > t.durationMs || e-f < 250)
                    throw new IllegalArgumentException("Verifique os tempos da música e das cenas.");
                t.startMs = start; t.endMs = end;
                t.inMs = f; t.outMs = e; t.volume = volume.getProgress(); t.repeat = repeat.isChecked();
                releaseSound(t); renderSoundTracks(); saveDraft(); pauseSounds();
                dialog.dismiss();
                toast("Trilha salva: cenas "+(first+1)+"–"+(last+1)+", "+formatMs(start)+" a "+formatMs(end));
            } catch (Exception error) {
                new AlertDialog.Builder(this).setTitle("Não foi possível aplicar")
                    .setMessage(friendly(error)).setPositiveButton("Corrigir", null).show();
            }
        }));
        dialog.show();
    }

        // Alteracoes visuais renderizadas no video exportado; corte seco, dip to black,
    // dip to white e clarão ciano. Crossfade real ainda nao e suportado pelo Media3.
    private static RgbMatrix makeTransitionEffect(String incoming, long inDurationMs,
                                                   String outgoing, long outDurationMs, long lengthMs) {
        return (presentationTimeUs,useHdr) -> {
            long ms=presentationTimeUs/1000L;
            float amount=0f; String effect=TRANSITIONS[0];
            long inMs=Math.min(inDurationMs,Math.max(1L,lengthMs/2L));
            long outMs=Math.min(outDurationMs,Math.max(1L,lengthMs/2L));
            if(!TRANSITIONS[0].equals(incoming) && ms>=0 && ms<inMs) {
                amount=Math.max(0f,1f-(float)ms/inMs);effect=incoming;
            }
            if(!TRANSITIONS[0].equals(outgoing) && ms>lengthMs-outMs && ms<=lengthMs+20L) {
                float fade=Math.min(1f,(ms-Math.max(0,lengthMs-outMs))/(float)outMs);
                if(fade>amount){amount=fade;effect=outgoing;}
            }
            amount=Math.min(1f,Math.max(0f,amount));
            float scale=1f-amount,r=0f,g=0f,b=0f;
            if(TRANSITIONS[2].equals(effect)){r=amount;g=amount;b=amount;}
            else if(TRANSITIONS[3].equals(effect)){r=.04f*amount;g=.85f*amount;b=.74f*amount;}
            return new float[]{scale,0,0,0,0,scale,0,0,0,0,scale,0,r,g,b,1};
        };
    }

    private void chooseTransition(int position) {
        if (exporting || position < 0 || position >= clips.size()-1) return;
        Clip clip = clips.get(position), next = clips.get(position+1);
        int max = (int)Math.max(100L,Math.min(2500L,Math.min(clip.length(),next.length())/2));
        LinearLayout form = dialogForm();
        form.addView(text("EFEITO ENTRE AS CENAS " + (position+1) + " E " + (position+2),13,Color.DKGRAY,true));
        Spinner types=new Spinner(this);
        ArrayAdapter<String> choices=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,TRANSITIONS);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        types.setAdapter(choices);
        types.setSelection(Math.max(0,Arrays.asList(TRANSITIONS).indexOf(clip.transition)));
        form.addView(types,margin(0,8));
        TextView durationLabel=text("Duração: "+String.format(Locale.US,"%.2f",clip.transitionDurationMs/1000.0)+" segundo(s)",13,Color.DKGRAY,true);
        form.addView(durationLabel,margin(0,15));
        SeekBar duration=new SeekBar(this);duration.setMax(Math.max(0,max-100));
        duration.setProgress(Math.max(0,Math.min(max-100,(int)clip.transitionDurationMs-100)));
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb,int p,boolean u) {
                durationLabel.setText("Duração: "+String.format(Locale.US,"%.2f",(p+100)/1000.0)+" segundo(s)");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        form.addView(duration);
        form.addView(text("Quanto mais longa a transição, mais suave a entrada e saída do efeito. O tempo máximo depende da duração das duas cenas.",12,Color.DKGRAY,false));
        new AlertDialog.Builder(this).setTitle("Transição ajustável").setView(form)
            .setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",(dialog,which)->{
                clip.transition=TRANSITIONS[types.getSelectedItemPosition()];
                clip.transitionDurationMs=duration.getProgress()+100L;
                renderVideoTimeline(); saveDraft(); updateTransitionPreview(currentTimelineMs());
            }).show();
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
                if (i > 0 && !TRANSITIONS[0].equals(clips.get(i-1).transition) && local < clips.get(i-1).transitionDurationMs) {
                    opacity = 1f - local/(float)Math.max(1L,clips.get(i-1).transitionDurationMs);
                    color = transitionColor(clips.get(i-1).transition);
                }
                if (i < clips.size()-1 && !TRANSITIONS[0].equals(clip.transition) && end-globalMs < clip.transitionDurationMs) {
                    float out = 1f - (end-globalMs)/(float)Math.max(1L,clip.transitionDurationMs);
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
        LinearLayout root = dialogForm();
        root.addView(text("Ouça antes, pesquise por estilo e adicione músicas em cenas diferentes.", 12, Color.DKGRAY, false));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Color.DKGRAY);
        search.setHint("Buscar música, emoção ou categoria");
        root.addView(search, margin(0, 7));

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (AudioEntry e : entries) unique.add(e.group);
        ArrayList<String> groups = new ArrayList<>(unique);
        Collections.sort(groups);
        groups.add(0, "Todas as categorias");
        Spinner category = new Spinner(this);
        ArrayAdapter<String> categories = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, groups);
        categories.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(categories);
        root.addView(category, margin(0, 8));
        TextView counter = text(entries.size() + " áudios disponíveis", 12, Color.DKGRAY, true);
        root.addView(counter, margin(0, 7));
        ScrollView scroll = new ScrollView(this);
        LinearLayout results = vertical();
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(330)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
            .setView(root).setNegativeButton("Fechar", null).create();
        Runnable refresh = () -> {
            results.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            String group = category.getSelectedItem() == null ? "" : category.getSelectedItem().toString();
            int count = 0;
            for (AudioEntry entry : entries) {
                if (!group.equals("Todas as categorias") && !entry.group.equals(group)) continue;
                if (!query.isEmpty() && !(entry.title+" "+entry.group).toLowerCase(Locale.ROOT).contains(query)) continue;
                count++;
                File cached = new File(new File(getFilesDir(), "library_audio"), entry.slug+".m4a");
                boolean available = !entry.online || (cached.isFile() && cached.length()>2000);
                TextView card = text("♫  " + entry.title + "\n      " + entry.group +
                    (available ? "    ✓ disponível" : "    ☁ baixar"), 14, Color.WHITE, true);
                card.setPadding(dp(12), dp(10), dp(10), dp(10));
                card.setBackground(rounded(CARD, 8));
                results.addView(card, margin(0, 6));
                card.setOnClickListener(v -> { dialog.dismiss(); showEntryActions(entry); });
            }
            counter.setText(count + " de " + entries.size() + " opções");
            if(count == 0) results.addView(text("Nenhum áudio para esta busca.", 13, Color.DKGRAY, false));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refresh.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        dialog.show();
        refresh.run();
    }

    private void showEntryActions(AudioEntry entry) {
        new AlertDialog.Builder(this).setTitle(entry.title)
            .setMessage(entry.group + " · faixa original Xandy Cut. " +
                (entry.online ? "Download uma vez e use depois sem internet." : "Disponível sem internet.") +
                "\n\nInserir: coloca em uma nova faixa. Trocar aqui: encerra a música atual neste ponto e começa a nova.")
            .setNeutralButton("▶ Ouvir", (d, w) -> acquireAudio(entry, file -> {
                if (catalogPlayer != null) { catalogPlayer.release(); catalogPlayer = null; }
                catalogPlayer = new ExoPlayer.Builder(this).build();
                catalogPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                catalogPlayer.prepare(); catalogPlayer.setVolume(.85f); catalogPlayer.play();
                toast("Ouvindo: " + entry.title);
            }))
            .setPositiveButton("+ Inserir", (d, w) -> acquireAudio(entry, file -> insertAudio(entry,file,false)))
            .setNegativeButton("Trocar aqui", (d, w) -> acquireAudio(entry, file -> insertAudio(entry,file,true)))
            .show();
    }

    private void insertAudio(AudioEntry entry, File file, boolean replaceMusic) {
        if (clips.isEmpty()) { toast("Adicione os vídeos antes de inserir a trilha."); return; }
        if (sounds.size() >= MAX_SOUND_TRACKS) { toast("Limite de 24 trilhas. Remova alguma antes."); return; }
        long d = duration(Uri.fromFile(file));
        if (d < 250) { toast("Arquivo de áudio inválido."); return; }
        long start = Math.min(currentTimelineMs(), Math.max(0,totalMs()-1));
        boolean effect = entry.group.equals("Efeito sonoro");
        if (replaceMusic && !effect) {
            ArrayList<SoundTrack> removing = new ArrayList<>();
            for (SoundTrack t : sounds) {
                if (t.effect || t.startMs > start || t.endMs <= start) continue;
                if (t.startMs == start) {
                    releaseSound(t); removing.add(t);
                } else {
                    t.endMs = start;
                    releaseSound(t);
                }
            }
            sounds.removeAll(removing);
        }
        SoundTrack t = new SoundTrack(Uri.fromFile(file), entry.title, d, start);
        t.effect = effect;
        t.repeat = !effect;
        t.endMs = totalMs();
        if (effect) t.endMs = Math.min(totalMs(),start+d);
        else if (replaceMusic) {
            for (SoundTrack later : sounds)
                if (!later.effect && later.startMs > start && later.startMs < t.endMs)
                    t.endMs = later.startMs;
        }
        sounds.add(t);
        renderSoundTracks(); saveDraft();
        toast(replaceMusic ? "Nova trilha a partir deste ponto" : "Trilha adicionada. Escolha as cenas.");
        editSound(t);
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
                videoEffects.add(makeTransitionEffect(incoming,
                    i>0 ? clips.get(i-1).transitionDurationMs : TRANSITION_MS,
                    outgoing,c.transitionDurationMs,c.length()));
            videoEffects.add(Presentation.createForWidthAndHeight(1080,1920,
                cropVertical ? Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP : Presentation.LAYOUT_SCALE_TO_FIT));
            b.setEffects(new Effects(originalVolume > 0 ? volumeEffects(originalVolume*c.volume/100) :
                Collections.emptyList(), videoEffects));
            videoItems.add(b.build());
        }
        ArrayList<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(originalVolume > 0
            ? EditedMediaItemSequence.withAudioAndVideoFrom(videoItems)
            : EditedMediaItemSequence.withVideoFrom(videoItems));
        for (SoundTrack s : sounds) {
            if (s.volume == 0 || s.startMs >= duration || s.length() <= 0 || s.endMs-s.startMs < 100) continue;
            EditedMediaItemSequence.Builder lane = new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_AUDIO));
            if (s.startMs > 0) lane.addGap(s.startMs * 1000L);
            long remaining = Math.max(0, Math.min(duration,s.endMs) - s.startMs);
            long audioPlayed = 0;
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
                audioPlayed += take;
                n++;
            } while (s.repeat && remaining >= 100 && n < 200);
            long silence = duration - s.startMs - audioPlayed;
            if (silence > 0) lane.addGap(silence * 1000L);
            sequences.add(lane.build());
        }
        Composition composition = new Composition.Builder(sequences).build();
        tempFile = new File(getCacheDir(), "xandy_export_" + System.currentTimeMillis() + ".mp4");
        setExporting(true);
        statusLabel.setText("Exportando 1080×1920 vertical com trilhas... mantenha o aplicativo aberto.");
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
            root.put("volume", originalVolume); root.put("cropVertical", cropVertical);
            JSONArray vc = new JSONArray();
            for (Clip c : clips) {
                JSONObject o = new JSONObject();
                o.put("uri", c.uri.toString()); o.put("name", c.name);
                o.put("duration", c.durationMs); o.put("from", c.fromMs); o.put("to", c.toMs);
                o.put("transition", c.transition); o.put("transitionDurationMs", c.transitionDurationMs); o.put("volume", c.volume);
                vc.put(o);
            }
            root.put("clips", vc);
            JSONArray va = new JSONArray();
            for (SoundTrack t : sounds) {
                JSONObject o = new JSONObject();
                o.put("uri", t.uri.toString()); o.put("name", t.name);
                o.put("duration", t.durationMs); o.put("from", t.inMs); o.put("to", t.outMs);
                o.put("start", t.startMs); o.put("end", t.endMs); o.put("phase", t.phaseMs); o.put("volume", t.volume); o.put("repeat", t.repeat); o.put("effect", t.effect);
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
            cropVertical = root.optBoolean("cropVertical", false);
            JSONArray vc = root.optJSONArray("clips");
            if (vc != null) for (int i = 0; i < vc.length(); i++) {
                JSONObject o = vc.getJSONObject(i);
                Uri uri = Uri.parse(o.getString("uri"));
                Clip c = new Clip(uri, o.getString("name"), o.getLong("duration"));
                c.fromMs = o.getLong("from"); c.toMs = o.getLong("to");
                c.transition = o.optString("transition", TRANSITIONS[0]);
                c.transitionDurationMs = o.optLong("transitionDurationMs", TRANSITION_MS);
                c.volume = o.optInt("volume", 100);
                clips.add(c);
            }
            JSONArray va = root.optJSONArray("sounds");
            if (va != null) for (int i = 0; i < va.length(); i++) {
                JSONObject o = va.getJSONObject(i);
                Uri uri = Uri.parse(o.getString("uri"));
                SoundTrack t = new SoundTrack(uri, o.getString("name"), o.getLong("duration"), o.getLong("start"));
                t.inMs = o.getLong("from"); t.outMs = o.getLong("to");
                t.volume = o.getInt("volume"); t.repeat = o.getBoolean("repeat");
                t.endMs = o.optLong("end", Math.min(totalMs(), t.startMs + (t.repeat ? totalMs() : t.length())));
                t.phaseMs = o.optLong("phase", 0L);
                t.effect = o.optBoolean("effect", false);
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