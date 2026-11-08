package com.bvalarm.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the resolved audio into app-private storage and verifies that the
 * platform media stack can actually decode it. If the DASH fMP4 audio is not
 * playable we fall back to the progressive mp4 (video+audio) stream, which is
 * bigger but always decodable.
 */
public final class RingtoneDownloader {

    public interface Listener {
        void onStage(String stage);

        void onProgress(int percent, long downloaded, long total);
    }

    private static final long MAX_DASH_BYTES = 60L * 1024 * 1024;
    private static final long MAX_FALLBACK_BYTES = 220L * 1024 * 1024;

    private RingtoneDownloader() {
    }

    public static File download(Context context, BiliApi.Track track, Listener listener,
                                AtomicBoolean cancelled) throws IOException {
        File dir = new File(context.getFilesDir(), "ringtones");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建铃声目录");
        }

        if (track.dashUrl != null) {
            listener.onStage("正在下载 B站 音频…");
            File part = new File(dir, track.bvid + ".m4a.part");
            try {
                fetchWithBackup(track.dashUrl, track.dashBackup, part, MAX_DASH_BYTES,
                        listener, cancelled);
                if (isPlayable(part)) {
                    return publish(part, new File(dir, track.bvid + ".m4a"));
                }
            } catch (IOException e) {
                if (cancelled.get()) {
                    throw new IOException("已取消");
                }
            } finally {
                if (part.exists()) {
                    part.delete();
                }
            }
        }

        if (track.durlUrl != null) {
            listener.onStage("音频格式不兼容，正在下载备用音轨…");
            File part = new File(dir, track.bvid + ".mp4.part");
            try {
                fetchWithBackup(track.durlUrl, track.durlBackup, part, MAX_FALLBACK_BYTES,
                        listener, cancelled);
                if (isPlayable(part)) {
                    return publish(part, new File(dir, track.bvid + ".mp4"));
                }
            } catch (IOException e) {
                if (cancelled.get()) {
                    throw new IOException("已取消");
                }
            } finally {
                if (part.exists()) {
                    part.delete();
                }
            }
        }
        throw new IOException("无法获取可播放的音频，换一个视频试试");
    }

    private static File publish(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧的铃声文件");
        }
        if (!part.renameTo(target)) {
            throw new IOException("铃声文件保存失败");
        }
        return target;
    }

    private static boolean isPlayable(File file) {
        if (!file.exists() || file.length() < 2048) {
            return false;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void fetchWithBackup(String url, String backup, File dest, long maxBytes,
                                        Listener listener, AtomicBoolean cancelled)
            throws IOException {
        IOException firstError = null;
        if (url != null) {
            try {
                fetch(url, dest, maxBytes, listener, cancelled);
                return;
            } catch (IOException e) {
                firstError = e;
                if (!dest.delete()) {
                    dest.deleteOnExit();
                }
            }
        }
        if (backup != null) {
            try {
                fetch(backup, dest, maxBytes, listener, cancelled);
                return;
            } catch (IOException e) {
                if (!dest.delete()) {
                    dest.deleteOnExit();
                }
                throw e;
            }
        }
        if (firstError != null) {
            throw firstError;
        }
        throw new IOException("没有可用的下载地址");
    }

    private static void fetch(String url, File dest, long maxBytes, Listener listener,
                              AtomicBoolean cancelled) throws IOException {
        HttpURLConnection conn = null;
        FileOutputStream out = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", BiliApi.UA);
            conn.setRequestProperty("Referer", BiliApi.REFERER);
            conn.setRequestProperty("Accept", "*/*");
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("下载失败（HTTP " + status + "）");
            }
            long total = conn.getContentLengthLong();
            if (total > maxBytes) {
                throw new IOException("音频文件过大");
            }
            in = conn.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.get()) {
                    throw new IOException("已取消");
                }
                out.write(buffer, 0, read);
                done += read;
                if (done > maxBytes) {
                    throw new IOException("音频文件过大");
                }
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(percent, done, total);
                }
            }
            out.flush();
        } catch (IOException e) {
            if (!dest.delete()) {
                dest.deleteOnExit();
            }
            throw e;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
