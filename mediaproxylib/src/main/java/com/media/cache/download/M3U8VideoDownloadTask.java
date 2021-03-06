package com.media.cache.download;

import com.media.cache.LocalProxyConfig;
import com.media.cache.VideoCacheInfo;
import com.media.cache.hls.M3U8;
import com.media.cache.hls.M3U8Ts;
import com.media.cache.hls.M3U8Utils;
import com.media.cache.listener.IVideoProxyCacheCallback;
import com.media.cache.utils.HttpUtils;
import com.media.cache.utils.LocalProxyThreadUtils;
import com.media.cache.utils.LocalProxyUtils;
import com.media.cache.utils.LogUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class M3U8VideoDownloadTask extends VideoDownloadTask {

    private static final String TS_PREFIX = "seg_";
    private final M3U8 mM3U8;
    private List<M3U8Ts> mTsList;
    private volatile int mCurTs;
    private int mTotalTs;
    private long mDuration;
    private final Object mFileLock = new Object();

    public M3U8VideoDownloadTask(LocalProxyConfig config,
                                 VideoCacheInfo info, M3U8 m3u8,
                                 HashMap<String, String> headers) {
        super(config, info, headers);
        this.mM3U8 = m3u8;
        this.mTsList = m3u8.getTsList();
        this.mTotalTs = mTsList.size();
        this.mCurTs = 0;
        this.mDuration = m3u8.getDuration();
        if (mDuration == 0) {
            mDuration = 1;
        }
        info.setTotalTs(mTotalTs);
        info.setCachedTs(mCurTs);
    }

    @Override
    public void startDownload(IVideoProxyCacheCallback callback) {
        startTimerTask();
        mIsPlaying = false;
        // Download hls resource from 0 index.
        seekToDownload(0, callback);
    }

    @Override
    public void resumeDownload() {
        LogUtils.i("M3U8VideoDownloadTask resumeDownload, curTs="+mCurTs);
        mShouldSuspendDownloadTask = false;
        seekToDownload(mCurTs, mCallback);

    }

    @Override
    public void seekToDownload(float seekPercent) {
        seekToDownload(seekPercent, mCallback);
    }

    @Override
    public void seekToDownload(long curPosition, long totalDuration) {
        pauseDownload();
        //Download hls resource from the seeking position.
        LogUtils.i("seekToDownload curPosition="+curPosition +", totalDuration="+totalDuration+", "+mDuration);
        if (mDuration != totalDuration && totalDuration != 0) {
            mDuration = totalDuration;
        }
        int curDownloadTs = mM3U8.getTsIndex(curPosition / 1000);
        mShouldSuspendDownloadTask = false;
        seekToDownload(curDownloadTs, mCallback);
    }

    @Override
    public void seekToDownload(float seekPercent, IVideoProxyCacheCallback callback) {
        pauseDownload();
        if (seekPercent < 0) {
            seekPercent = 0f;
        }
        //Download hls resource from the seeking position.
        long curPosition = (long)(seekPercent * 1.0f / 100 * mDuration);
        LogUtils.i("seekToDownload curPosition="+curPosition);
        int curDownloadTs = mM3U8.getTsIndex(curPosition);
        mShouldSuspendDownloadTask = false;
        seekToDownload(curDownloadTs, callback);
    }

    @Override
    public void seekToDownload(int curDownloadTs, IVideoProxyCacheCallback callback) {
        mCallback = callback;
        if (mInfo.getIsCompleted()) {
            LogUtils.i("M3U8VideoDownloadTask local file.");
            notifyVideoReady();
            notifyCacheProgress();
            return;
        }
        mCurTs = curDownloadTs;
        LogUtils.i("seekToDownload curDownloadTs = " + curDownloadTs);
        mDownloadExecutor = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int index = curDownloadTs; index < mTotalTs; index++) {
            if (mDownloadExecutor.isShutdown()) {
                break;
            }
            M3U8Ts ts = mTsList.get(index);
            String tsName = TS_PREFIX + index + ".ts";
            File tsFile = new File(mSaveDir, tsName);
            mDownloadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (isM3U8FileExisted()) {
                            notifyVideoReady();
                        } else {
                            createM3U8File();
                            notifyVideoReady();
                        }
                        downloadTsTask(ts, tsFile, tsName);
                    } catch (Exception e) {
                        LogUtils.w( "M3U8TsDownloadThread download failed, exception="+e);
                        notifyFailed(e);
                    }
                }
            });
        }

        notifyCacheFinished();
    }

    private void downloadTsTask(M3U8Ts ts, File tsFile, String tsName) throws Exception {
        if (!tsFile.exists()) {
            //ts is network resource, download ts file then rename it to local file.
            downloadFile(ts.getUrl(), tsFile);
        }

        if (tsFile.exists()) {
            //rename network ts name to local file name.
            ts.setName(tsName);
            ts.setTsSize(tsFile.length());
            mCurTs++;
            notifyCacheProgress();
        }
    }

    //Just for BaseVideoDownloadTask.
    @Override
    public void seekToDownload(long curLength, IVideoProxyCacheCallback callback) {

    }

    @Override
    public void pauseDownload() {
        if (mDownloadExecutor != null && !mDownloadExecutor.isShutdown()) {
            mDownloadExecutor.shutdownNow();
            mShouldSuspendDownloadTask = true;
        }
        updateProxyCacheInfo();
    }

    @Override
    public void stopDownload() {
        if (mDownloadExecutor != null && !mDownloadExecutor.isShutdown()) {
            mDownloadExecutor.shutdownNow();
            mShouldSuspendDownloadTask = true;
        }
        updateProxyCacheInfo();
        checkCacheFile(mSaveDir);

        cancelTimer();
    }

    private boolean isM3U8FileExisted() {
        synchronized (mFileLock) {
            return new File(mSaveDir, "proxy.m3u8").exists();
        }
    }

    private void notifyVideoReady() {
        if (mCallback != null && !mIsPlaying) {
            LogUtils.i( "M3U8VideoDownloadTask notifyVideoReady");
            String url = String.format(Locale.US, "http://%s:%d/%s/%s", mConfig.getHost(), mConfig.getPort(), mSaveName, "proxy.m3u8");
            mCallback.onCacheReady(mInfo.getVideoUrl(), url);//Uri.fromFile(mM3u8Help.getFile()).toString());
            mIsPlaying = true;
        }
    }

    private void notifyFailed(Exception e) {
        checkCacheFile(mSaveDir);
        //InterruptedIOException is just interrupted by external operation.
        if (e instanceof InterruptedException) {
            if (e instanceof SocketTimeoutException) {
                LogUtils.w("M3U8VideoDownloadTask notifyFailed: " + e);
                resumeDownload();
                return;
            }
            pauseDownload();
            writeProxyCacheInfo();
            return;
        } else if (e instanceof MalformedURLException){
            String parsedString = "no protocol: ";
            if (e.toString().contains(parsedString)) {
                String fileName = e.toString().substring(e.toString().indexOf(parsedString) + parsedString.length());
                LogUtils.w(fileName + " not existed.");
            }
            return;
        }
        if (mCallback != null){
            mCallback.onCacheFailed(mInfo.getVideoUrl(), e);
        }
    }

    private void updateProxyCacheInfo() {
        boolean isCompleted = true;
        for (M3U8Ts ts : mTsList) {
            File tsFile = new File(mSaveDir, ts.getIndexName());
            if (!tsFile.exists()) {
                isCompleted = false;
                break;
            }
        }
        mInfo.setIsCompleted(isCompleted);
        if (isCompleted) {
            writeProxyCacheInfo();
        }
    }

    private void writeProxyCacheInfo() {
        if (mType == OPERATE_TYPE.WRITED) {
            return;
        }
        LocalProxyThreadUtils.submitRunnableTask(new Runnable() {
            @Override
            public void run() {
                LogUtils.i("writeProxyCacheInfo : " + mInfo);
                LocalProxyUtils.writeProxyCacheInfo(mInfo, mSaveDir);
            }
        });
        if (mType == OPERATE_TYPE.DEFAULT && mInfo.getIsCompleted()) {
            mType = OPERATE_TYPE.WRITED;
        }
    }

    private void notifyCacheFinished() {
        if (mCallback != null) {
            updateProxyCacheInfo();
            if (mInfo.getIsCompleted()) {
                mCallback.onCacheFinished(mInfo.getVideoUrl());
                checkCacheFile(mSaveDir);
            }
        }
    }

    private void notifyCacheProgress() {
        if (mCallback != null) {
            mCurrentCachedSize = 0;
            for (M3U8Ts ts : mTsList) {
                mCurrentCachedSize += ts.getTsSize();
            }
            if (mCurrentCachedSize == 0) {
                mCurrentCachedSize = LocalProxyUtils.countTotalSize(mSaveDir);
            }
            if (mInfo.getIsCompleted()) {
                mCurTs = mTotalTs;
                mCallback.onCacheFinished(mInfo.getVideoUrl());
                checkCacheFile(mSaveDir);
                mCallback.onCacheProgressChanged(mInfo.getVideoUrl(), 100,
                        mCurrentCachedSize, mM3U8);
                return;
            }
            if (mCurTs > mTotalTs) {
                mCurTs = mTotalTs;
            }
            mInfo.setCachedTs(mCurTs);
            mM3U8.setCurTsIndex(mCurTs);
            int percent =
                    (int)(mCurTs * 1.0f * 100 / mTotalTs);
            mCallback.onCacheProgressChanged(mInfo.getVideoUrl(), percent,
                    mCurrentCachedSize, mM3U8);
        }
    }

    private static final int REDIRECTED_COUNT = 3;

    public void downloadFile(String url, File file) throws Exception {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            connection = openConnection(url);
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpUtils.RESPONSE_OK) {
                inputStream = connection.getInputStream();
                saveFile(inputStream, file);
            }
        }catch (Exception e) {
            throw e;
        }finally {
            if (connection != null)
                connection.disconnect();
            LocalProxyUtils.close(inputStream);
        }

    }

    private HttpURLConnection openConnection(String videoUrl)
            throws Exception {
        HttpURLConnection connection;
        boolean redirected;
        int redirectedCount = 0;
        do {
            URL url = new URL(videoUrl);
            connection = (HttpURLConnection)url.openConnection();

            if (mConfig.shouldIgnoreAllCertErrors() && connection instanceof HttpsURLConnection) {
                trustAllCert((HttpsURLConnection)(connection));
            }
            connection.setConnectTimeout(mConfig.getConnTimeOut());
            connection.setReadTimeout(mConfig.getReadTimeOut());
            if (mHeaders != null) {
                for (Map.Entry<String, String> item : mHeaders.entrySet()) {
                    connection.setRequestProperty(item.getKey(), item.getValue());
                }
            }
            int code = connection.getResponseCode();
            redirected = code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER;
            if (redirected) {
                redirectedCount++;
                connection.disconnect();
            }
            if (redirectedCount > REDIRECTED_COUNT) {
                throw new Exception("Too many redirects: " +
                        redirectedCount);
            }
        } while (redirected);
        return connection;
    }

    private void saveFile(InputStream inputStream, File file) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            int len = 0;
            byte[] buf = new byte[BUFFER_SIZE];
            while ((len = inputStream.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
        } catch (IOException e) {
            LogUtils.w(file.getAbsolutePath() + " saveFile failed, exception="+e);
            if (file.exists()) {
                file.delete();
            }
        } finally {
            LocalProxyUtils.close(inputStream);
            LocalProxyUtils.close(fos);
        }
    }

    private void createM3U8File() throws IOException {
        synchronized (mFileLock) {
            File tempM3U8File = new File(mSaveDir, "temp.m3u8");
            if (tempM3U8File.exists()) {
                tempM3U8File.delete();
            }

            BufferedWriter bfw = new BufferedWriter(new FileWriter(tempM3U8File, false));
            bfw.write(M3U8Utils.PLAYLIST_HEADER + "\n");
            bfw.write(M3U8Utils.TAG_VERSION + ":" + mM3U8.getVersion() + "\n");
            bfw.write(M3U8Utils.TAG_MEDIA_SEQUENCE + ":" + mM3U8.getSequence()+"\n");

            bfw.write(M3U8Utils.TAG_TARGET_DURATION + ":" + mM3U8.getTargetDuration() + "\n");

            for (M3U8Ts m3u8Ts : mTsList) {
                if (m3u8Ts.hasKey()) {
                    if (m3u8Ts.getMethod() != null) {
                        String key = "METHOD=" + m3u8Ts.getMethod();
                        if (m3u8Ts.getKeyUri() != null) {
                            File keyFile = new File(mSaveDir, m3u8Ts.getLocalKeyUri());
                            if (!m3u8Ts.isMessyKey() && keyFile.exists()) {
                                key += ",URI=\"" + m3u8Ts.getLocalKeyUri() + "\"";
                            } else {
                                key += ",URI=\"" + m3u8Ts.getKeyUri() + "\"";
                            }
                        }
                        if (m3u8Ts.getKeyIV() != null) {
                            key += ",IV=" + m3u8Ts.getKeyIV();
                        }
                        bfw.write(M3U8Utils.TAG_KEY + ":" + key + "\n");
                    }
                }
                if (m3u8Ts.hasDiscontinuity()) {
                    bfw.write(M3U8Utils.TAG_DISCONTINUITY+"\n");
                }
                bfw.write(M3U8Utils.TAG_MEDIA_DURATION + ":" + m3u8Ts.getDuration()+",\n");
                bfw.write(m3u8Ts.getProxyUrl(mConfig.getHost(), mConfig.getPort(), mSaveName));
                bfw.newLine();
            }
            bfw.write(M3U8Utils.TAG_ENDLIST);
            bfw.flush();
            bfw.close();

            File localM3U8File = new File(mSaveDir, "proxy.m3u8");
            if (localM3U8File.exists()) {
                localM3U8File.delete();
            }
            tempM3U8File.renameTo(localM3U8File);
        }
    }
}

