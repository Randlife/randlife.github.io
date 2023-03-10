/*
 * Symphony - A modern community (forum/BBS/SNS/blog) platform written in Java.
 * Copyright (C) 2012-present, b3log.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.symphony.service;

import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.b3log.latke.Latkes;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.URLs;
import org.b3log.symphony.processor.FileUploadProcessor;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Audio management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.1.0.0, Dec 16, 2018
 * @since 2.1.0
 */
@Service
public class AudioMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = LogManager.getLogger(AudioMgmtService.class);

    private static final String BAIDU_API_KEY = Symphonys.BAIDU_YUYIN_API_KEY;
    private static final String BAIDU_SECRET_KEY = Symphonys.BAIDU_YUYIN_SECRET_KEY;
    private static String BAIDU_ACCESS_TOKEN;
    private static long BAIDU_ACCESS_TOKEN_TIME;

    private static void refreshBaiduAccessToken() {
        if (StringUtils.isBlank(BAIDU_API_KEY) || StringUtils.isBlank(BAIDU_SECRET_KEY)) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (StringUtils.isNotBlank(BAIDU_ACCESS_TOKEN) && now - 1000 * 60 * 60 * 6 < BAIDU_ACCESS_TOKEN_TIME) {
            return;
        }

        try {
            final URL url = new URL("https://openapi.baidu.com/oauth/2.0/token?grant_type=client_credentials"
                    + "&client_id=" + BAIDU_API_KEY + "&client_secret=" + BAIDU_SECRET_KEY);

            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");

            try (final InputStream inputStream = conn.getInputStream()) {
                final String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);

                conn.disconnect();

                final JSONObject result = new JSONObject(content);
                BAIDU_ACCESS_TOKEN = result.optString("access_token", null);
                BAIDU_ACCESS_TOKEN_TIME = now;
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Requires Baidu Yuyin access token failed", e);
        }
    }

    private static byte[] baiduTTS(final String text, final String uid) {
        if (StringUtils.isBlank(BAIDU_API_KEY) || StringUtils.isBlank(BAIDU_SECRET_KEY)) {
            return null;
        }

        if (null == BAIDU_ACCESS_TOKEN) {
            refreshBaiduAccessToken();
        }

        if (null == BAIDU_ACCESS_TOKEN) {
            LOGGER.warn("Please configure [baidu.yuyin.*] in symphony.properties");

            return null;
        }

        try {
            final URL url = new URL("http://tsn.baidu.com/text2audio?grant_type=client_credentials"
                    + "&client_id=" + BAIDU_API_KEY + "&client_secret=" + BAIDU_SECRET_KEY);

            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            try (final OutputStream outputStream = conn.getOutputStream()) {
                IOUtils.write("tex=" + URLs.encode(StringUtils.substring(text, 0, 1024))
                        + "&lan=zh&cuid=" + uid + "&spd=6&pit=6&ctp=1&tok=" + BAIDU_ACCESS_TOKEN, outputStream, "UTF-8");
            }

            byte[] ret;
            try (final InputStream inputStream = conn.getInputStream()) {
                final int responseCode = conn.getResponseCode();
                final String contentType = conn.getContentType();

                if (200 != responseCode || !"audio/mp3".equals(contentType)) {
                    final String msg = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                    LOGGER.warn("Baidu Yuyin TTS failed: " + msg);
                    conn.disconnect();

                    return null;
                }

                ret = IOUtils.toByteArray(inputStream);
            }

            conn.disconnect();

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Requires Baidu Yuyin access token failed", e);
        }

        return null;
    }

    /**
     * Remove speech audio files in object storage.
     *
     * @param audioURL the specified speech audio URL
     */
    public void removeAudioFile(final String audioURL) {
        try {
            LOGGER.log(Level.INFO, "Removing audio file [" + audioURL + "]");

            if (Symphonys.QN_ENABLED) {
                final Auth auth = Auth.create(Symphonys.UPLOAD_QINIU_AK, Symphonys.UPLOAD_QINIU_SK);
                final BucketManager bucketManager = new BucketManager(auth, new Configuration());
                final String fileKey = StringUtils.replace(audioURL, Symphonys.UPLOAD_QINIU_DOMAIN + "/", "");
                bucketManager.delete(Symphonys.UPLOAD_QINIU_BUCKET, fileKey);
            } else {
                final String fileName = StringUtils.replace(audioURL, Latkes.getServePath() + "/upload", "");
                final File file = new File(Symphonys.UPLOAD_LOCAL_DIR + fileName);
                FileUtils.deleteQuietly(file);
            }

            LOGGER.log(Level.INFO, "Removed audio file [" + audioURL + "]");
        } catch (final Exception e) {
            if (e instanceof QiniuException) {
                try {
                    LOGGER.log(Level.ERROR, "Removes audio failed [" + audioURL + "], Qiniu exception body [" +
                            ((QiniuException) e).response.bodyString() + "]");
                } catch (final Exception qe) {
                    LOGGER.log(Level.ERROR, "Removes audio and parse result exception", qe);
                }
            } else {
                LOGGER.log(Level.ERROR, "Removes audio failed [" + audioURL + "]", e);
            }
        }
    }

    /**
     * Text to speech.
     *
     * @param text   the specified text
     * @param type   the specified type, "article"/"comment"
     * @param textId the specified text id, article id or comment id
     * @param uid    the specified user id
     * @return speech URL, returns {@code ""} if TTS failed
     */
    public String tts(final String text, final String type, final String textId, final String uid) {
        final byte[] bytes = baiduTTS(text, uid);
        if (null == bytes) {
            return "";
        }

        String ret;
        try {
            if (Symphonys.QN_ENABLED) {
                final Auth auth = Auth.create(Symphonys.UPLOAD_QINIU_AK, Symphonys.UPLOAD_QINIU_SK);
                final UploadManager uploadManager = new UploadManager(new Configuration());
                final BucketManager bucketManager = new BucketManager(auth, new Configuration());
                final String fileKey = "audio/" + type + "/" + textId + ".mp3";
                try {
                    bucketManager.delete(Symphonys.UPLOAD_QINIU_BUCKET, fileKey);
                } catch (final Exception e) {
                    // ignore
                }
                uploadManager.put(bytes, fileKey, auth.uploadToken(Symphonys.UPLOAD_QINIU_BUCKET), null, "audio/mp3", false);
                ret = Symphonys.UPLOAD_QINIU_DOMAIN + "/" + fileKey;
            } else {
                String fileName = UUID.randomUUID().toString().replaceAll("-", "") + ".mp3";
                fileName = FileUploadProcessor.genFilePath(fileName);
                try (final OutputStream output = new FileOutputStream(Symphonys.UPLOAD_LOCAL_DIR + fileName)) {
                    IOUtils.write(bytes, output);
                }

                ret = Latkes.getServePath() + "/upload/" + fileName;
            }

            return ret;
        } catch (final Exception e) {
            if (e instanceof QiniuException) {
                try {
                    LOGGER.log(Level.ERROR, "Uploads audio failed [type=" + type + ", textId=" + textId + "], Qiniu exception body [" +
                            ((QiniuException) e).response.bodyString() + "]");
                } catch (final Exception qe) {
                    LOGGER.log(Level.ERROR, "Uploads audio and parse result exception", qe);
                }
            } else {
                LOGGER.log(Level.ERROR, "Uploads audio failed [type=" + type + ", textId=" + textId + "]", e);
            }
        }

        return "";
    }
}
