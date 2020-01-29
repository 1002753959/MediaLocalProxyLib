package com.android.media;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.media.utils.ScreenUtils;
import com.android.player.CommonPlayer;
import com.android.player.IPlayer;
import com.android.player.PlayerType;
import com.media.cache.utils.LocalProxyUtils;
import com.media.cache.utils.LogUtils;

import java.io.IOException;

public class PlayerActivity extends Activity implements View.OnClickListener {

    private TextureView mVideoView;
    private ImageButton mControlBtn;
    private TextView mTimeView;
    private SeekBar mProgressView;

    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mVideoWidth;
    private int mVideoHeight;
    private long mDuration = 0L;

    private CommonPlayer mPlayer;
    private Surface mSurface;
    private String mUrl = "http://gv.vivo.com.cn/appstore/gamecenter/upload/video/201701/2017011314414026850.mp4";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        mUrl = getIntent().getStringExtra("url");
        mSurfaceWidth = ScreenUtils.getScreenWidth(this);
        initViews();
    }

    private void initViews() {
        mVideoView = (TextureView) findViewById(R.id.video_view);
        mTimeView = (TextView) findViewById(R.id.video_time_view);
        mProgressView = (SeekBar) findViewById(R.id.video_progress_view);
        mControlBtn = (ImageButton) findViewById(R.id.video_control_btn);


        mControlBtn.setOnClickListener(this);
        mVideoView.setSurfaceTextureListener(mSurfaceTextureListener);
        mProgressView.setOnSeekBarChangeListener(mSeekBarChangeListener);
    }

    private void initPlayer() throws IOException {
        mPlayer = new CommonPlayer(this, PlayerType.EXO_PLAYER);
        Uri uri = Uri.parse(mUrl);
        mPlayer.setDataSource(this, uri);
        mPlayer.setSurface(mSurface);
        mPlayer.setOnPreparedListener(mPreparedListener);
        mPlayer.setOnVideoSizeChangedListener(mVideoSizeChangeListener);
        mPlayer.prepareAsync();
    }

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mSurface = new Surface(surface);
            try {
                initPlayer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private SeekBar.OnSeekBarChangeListener mSeekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (mPlayer != null) {
                mHandler.removeMessages(MSG_UPDATE_PROGRESS);
            }
            LogUtils.d("onStartTrackingTouch progress="+mProgressView.getProgress());
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            LogUtils.d("onStopTrackingTouch progress="+mProgressView.getProgress());

            if (mPlayer != null) {
                int progress = mProgressView.getProgress();
                int seekPosition = (int)(progress * 1.0f / MAX_PROGRESS * mDuration);
                mPlayer.seekTo(seekPosition);

                mHandler.sendEmptyMessage(MSG_UPDATE_PROGRESS);
            }
        }
    };

    @Override
    protected void onStop() {
        super.onStop();
        if (mPlayer != null) {
            mPlayer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        doReleasePlayer();
    }

    private IPlayer.OnPreparedListener mPreparedListener = new IPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(IPlayer mp) {
            doPlayVideo();
        }
    };

    private IPlayer.OnVideoSizeChangedListener mVideoSizeChangeListener = new IPlayer.OnVideoSizeChangedListener() {

        @Override
        public void onVideoSizeChanged(IPlayer mp, int width, int height, int rotationDegree, float pixelRatio) {

            LogUtils.d("PlayerActivity onVideoSizeChanged width="+width+", height="+height);
            mVideoWidth = width;
            mVideoHeight = height;
            mSurfaceHeight = (int)(mSurfaceWidth * mVideoHeight * 1.0f / mVideoWidth);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(mSurfaceWidth, mSurfaceHeight);
            mVideoView.setLayoutParams(params);
        }
    };

    private static final int MSG_UPDATE_PROGRESS = 0x1;
    private static final int MSG_UPDATE_CACHE_PROGRESS = 0x2;
    private static final int MAX_PROGRESS = 1000;

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_UPDATE_PROGRESS) {
                updateProgressView();
            } else if (msg.what == MSG_UPDATE_CACHE_PROGRESS) {

            }
        }
    };

    @Override
    public void onClick(View view) {
        if(view == mControlBtn) {

        }
    }

    private void doPlayVideo() {
        if (mPlayer != null) {
            mPlayer.start();
            mDuration = mPlayer.getDuration();
            LogUtils.d("total duration ="+mDuration +", timeString="+ LocalProxyUtils.getVideoTimeString(mDuration));
            mHandler.sendEmptyMessage(MSG_UPDATE_PROGRESS);
        }
    }

    private void updateProgressView() {
        if (mPlayer != null) {
            long currentPosition = mPlayer.getCurrentPosition();
            mTimeView.setText(LocalProxyUtils.getVideoTimeString(currentPosition) + " / " + LocalProxyUtils.getVideoTimeString(mDuration));

            mProgressView.setProgress((int)(1000 *  currentPosition * 1.0f / mDuration));
        }
        mHandler.sendEmptyMessageDelayed(MSG_UPDATE_PROGRESS, 1000);
    }

    private void doReleasePlayer() {
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
    }
}
