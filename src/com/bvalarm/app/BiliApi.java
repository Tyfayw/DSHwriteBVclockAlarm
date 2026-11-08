package com.bvalarm.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a BV id (or a bilibili video link) into a downloadable audio stream.
 *
 * Strategy:
 *   1. /x/web-interface/view  -> title, cid, pages
 *   2. /x/player/playurl (fnval=16) -> DASH audio (small, AAC in fMP4)
 *   3. if no DASH audio -> playurl (fnval=0) -> progressive mp4 (video+audio, used as fallback)
 *
 * All requests need a desktop UA and a bilibili Referer, otherwise the CDN answers 403.
 */
public final class BiliApi {

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    public static final String REFERER = "https://www.bilibili.com/";

    private static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAY_URL = "https://api.bilibili.com/x/player/playurl";

    private static final Pattern BV_PATTERN = Pattern.compile("(?i)BV([0-9A-Za-z]{10})");
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");

    private BiliApi() {
    }

    public static class BiliException extends Exception {
        public BiliException(String message) {
            super(message);
        }
    }

    public static class Track {
        public String bvid = "";
        public String cid = "";
        public String title = "";
        public String owner = "";
        public long durationSec;
        public int pageIndex = 1;
        public int pageCount = 1;

        public String dashUrl;
        public String dashBackup;
        public String durlUrl;
        public String durlBackup;
        public long durlSize;

        public String durationText() {
            long m = durationSec / 60;
            long s = durationSec % 60;
            return String.format(java.util.Locale.US, "%d:%02d", m, s);
        }
    }

    public static Track resolve(String rawInput) throws BiliException {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            throw new BiliException("请输入 BV 号或视频链接");
        }
        if (input.contains("b23.tv") || input.contains("bili2233")) {
            input = expandShortLink(input);
        }
        Matcher bv = BV_PATTERN.matcher(input);
        if (!bv.find()) {
            throw new BiliException("没有找到 BV 号。请输入形如 BV1GJ411x7h7 的号码或视频链接");
        }
        String bvid = "BV" + bv.group(1);

        int page = 1;
        Matcher pm = PAGE_PATTERN.matcher(input);
        if (pm.find()) {
            try {
                page = Integer.parseInt(pm.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        Track track = new Track();
        track.bvid = bvid;

        JSONObject view = getJson(VIEW_URL + "?bvid=" + bvid);
        int code = view.optInt("code", -1);
        if (code != 0) {
            throw new BiliException(friendly(code, view.optString("message", "")));
        }
        JSONObject data = view.optJSONObject("data");
        if (data == null) {
            throw new BiliException("B站没有返回视频信息，请稍后重试");
        }

        track.title = clean(data.optString("title", ""));
        JSONObject owner = data.optJSONObject("owner");
        track.owner = owner == null ? "" : clean(owner.optString("name", ""));
        track.durationSec = data.optLong("duration", 0);
        track.cid = data.optString("cid", "");

        JSONArray pages = data.optJSONArray("pages");
        if (pages != null && pages.length() > 0) {
            track.pageCount = pages.length();
            int index = Math.min(Math.max(page, 1), pages.length()) - 1;
            JSONObject pageObj = pages.optJSONObject(index);
            if (pageObj != null) {
                track.cid = pageObj.optString("cid", track.cid);
                track.pageIndex = index + 1;
            }
        }
        if (track.cid.isEmpty()) {
            throw new BiliException("没有拿到视频的 cid，无法获取音频");
        }

        JSONObject play = getJson(PLAY_URL + "?bvid=" + bvid + "&cid=" + track.cid
                + "&fnval=16&fnver=0&fourk=1");
        int playCode = play.optInt("code", -1);
        String playError = null;
        if (playCode == 0) {
            JSONObject playData = play.optJSONObject("data");
            pickDashAudio(playData == null ? null : playData.optJSONObject("dash"), track);
        } else {
            playError = friendly(playCode, play.optString("message", ""));
        }

        if (track.dashUrl == null) {
            JSONObject fallback = getJson(PLAY_URL + "?bvid=" + bvid + "&cid=" + track.cid
                    + "&qn=16&fnval=0&fourk=0&platform=html5");
            if (fallback.optInt("code", -1) == 0) {
                JSONObject playData = fallback.optJSONObject("data");
                pickDurl(playData == null ? null : playData.optJSONArray("durl"), track);
            } else if (playError == null) {
                playError = friendly(fallback.optInt("code", -1),
                        fallback.optString("message", ""));
            }
        }

        if (track.dashUrl == null && track.durlUrl == null) {
            throw new BiliException(playError != null ? playError : "这个视频没有可用的音频流，换一个视频试试");
        }
        return track;
    }

    private static void pickDashAudio(JSONObject dash, Track track) {
        if (dash == null) {
            return;
        }
        JSONArray audio = dash.optJSONArray("audio");
        if (audio == null) {
            return;
        }
        JSONObject best = null;
        int bestRank = -1;
        long bestBandwidth = -1;
        for (int i = 0; i < audio.length(); i++) {
            JSONObject item = audio.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String url = firstNonEmpty(item.optString("baseUrl", ""), item.optString("base_url", ""));
            if (url.isEmpty()) {
                continue;
            }
            int rank = qualityRank(item.optInt("id", 0));
            long bandwidth = item.optLong("bandwidth", 0);
            if (best == null || rank > bestRank || (rank == bestRank && bandwidth > bestBandwidth)) {
                best = item;
                bestRank = rank;
                bestBandwidth = bandwidth;
            }
        }
        if (best == null) {
            return;
        }
        track.dashUrl = normalizeUrl(firstNonEmpty(best.optString("baseUrl", ""),
                best.optString("base_url", "")));
        track.dashBackup = normalizeUrl(firstFromArray(best, "backupUrl", "backup_url"));
    }

    private static void pickDurl(JSONArray durl, Track track) {
        if (durl == null || durl.length() == 0) {
            return;
        }
        JSONObject first = durl.optJSONObject(0);
        if (first == null) {
            return;
        }
        String url = first.optString("url", "");
        if (url.isEmpty()) {
            return;
        }
        track.durlUrl = normalizeUrl(url);
        track.durlBackup = normalizeUrl(firstFromArray(first, "backup_url", "backup_url"));
        track.durlSize = first.optLong("size", 0);
    }

    private static int qualityRank(int id) {
        if (id == 30280) {
            return 4; // 192 kbps
        }
        if (id == 30232) {
            return 3; // 132 kbps
        }
        if (id == 30216) {
            return 2; // 64 kbps
        }
        if (id == 30250 || id == 30251) {
            return 1; // dolby / flac, often needs a paid account
        }
        return 1;
    }

    private static String firstFromArray(JSONObject o, String camel, String snake) {
        JSONArray arr = o.optJSONArray(camel);
        if (arr == null) {
            arr = o.optJSONArray(snake);
        }
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        String single = o.optString(camel, "");
        if (single.isEmpty()) {
            single = o.optString(snake, "");
        }
        return single;
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && a.length() > 0 ? a : (b == null ? "" : b);
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        if (u.isEmpty()) {
            return null;
        }
        if (u.startsWith("//")) {
            u = "https:" + u;
        }
        if (u.startsWith("http://")) {
            u = "https://" + u.substring("http://".length());
        }
        return u;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String expandShortLink(String url) {
        String target = url;
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            conn.getResponseCode();
            String resolved = conn.getURL().toString();
            return resolved == null ? url : resolved;
        } catch (Exception e) {
            return url;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static JSONObject getJson(String url) throws BiliException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.connect();
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            if (status != 200) {
                throw new BiliException("B站接口请求失败（HTTP " + status + "），请检查网络");
            }
            return new JSONObject(body);
        } catch (BiliException e) {
            throw e;
        } catch (IOException e) {
            throw new BiliException("网络连接失败，请检查手机网络后重试");
        } catch (JSONException e) {
            throw new BiliException("B站返回的数据无法解析，请稍后重试");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String friendly(int code, String message) {
        switch (code) {
            case -400:
                return "请求有误，请检查 BV 号是否正确";
            case -404:
                return "视频不存在或已被删除";
            case -403:
                return "没有访问这个视频的权限";
            case -10403:
                return "这个视频有地区限制，无法获取音频";
            case 62002:
                return "视频不可见（可能已删除或设为私密）";
            case 87008:
                return "这个视频的音频需要大会员，换个视频吧";
            case -352:
                return "B站风控拦截了请求，请稍后再试";
            default:
                String detail = message == null ? "" : message.trim();
                return "B站返回错误（" + code + "）" + (detail.isEmpty() ? "" : "：" + detail);
        }
    }
}
