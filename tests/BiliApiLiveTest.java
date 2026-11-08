import com.bvalarm.app.BiliApi;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Live check of the resolver against api.bilibili.com (desktop JVM). */
public class BiliApiLiveTest {

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();

        BiliApi.Track track = BiliApi.resolve("BV1GJ411x7h7");
        out.append("resolved bvid=").append(track.bvid)
                .append(" cid=").append(track.cid)
                .append(" duration=").append(track.durationSec)
                .append(" page=").append(track.pageIndex).append("/").append(track.pageCount)
                .append("\ntitle=").append(track.title)
                .append("\nowner=").append(track.owner)
                .append("\ndash=").append(track.dashUrl == null ? "null" : track.dashUrl.substring(0, Math.min(90, track.dashUrl.length())))
                .append("\ndashBackup=").append(track.dashBackup == null ? "null" : "yes")
                .append("\ndurl=").append(track.durlUrl == null ? "null" : "yes")
                .append("\n");

        // URL form should resolve to the same video.
        BiliApi.Track fromUrl = BiliApi.resolve("https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.999");
        out.append("urlForm bvid=").append(fromUrl.bvid).append(" cid=").append(fromUrl.cid).append("\n");

        // Download a slice of the resolved audio to prove the CDN accepts our headers.
        HttpURLConnection conn = (HttpURLConnection) new URL(track.dashUrl).openConnection();
        conn.setRequestProperty("User-Agent", BiliApi.UA);
        conn.setRequestProperty("Referer", BiliApi.REFERER);
        conn.setRequestProperty("Range", "bytes=0-65535");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int status = conn.getResponseCode();
        byte[] head = new byte[32];
        int read = 0;
        try (InputStream in = conn.getInputStream()) {
            int n;
            while (read < head.length && (n = in.read(head, read, head.length - read)) > 0) {
                read += n;
            }
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < read; i++) {
            hex.append(String.format("%02x", head[i]));
        }
        out.append("audioHTTP=").append(status).append(" headHex=").append(hex).append("\n");

        // Bad BV should produce a friendly, non-empty error.
        try {
            BiliApi.resolve("BV0000000000");
            out.append("badBv=NO_ERROR\n");
        } catch (BiliApi.BiliException e) {
            out.append("badBv=").append(e.getMessage()).append("\n");
        }

        Files.write(Paths.get("bvalarm/tests/live-result.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
    }
}
