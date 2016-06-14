package com.windriver.videowalldemo;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.windriver.gfxtweaksample.gles.EglCore;
import com.windriver.gfxtweaksample.gles.GlUtil;
import com.windriver.gfxtweaksample.gles.Texture2dProgram;
import com.windriver.gfxtweaksample.gles.WindowSurface;

import java.nio.FloatBuffer;

public class MultipleSurfaceView extends Activity {

    private static final String TAG = "MultipleSurfaceView";
    final int WIDTH = 1600;
    final int HEIGHT = 800;
    final int DENSITY = 150;

    private DemoPresentation mPresentation;
    private SurfaceTexture mSurfaceTexture;

    static final int MSG_START_PRESENTATION = 1;
    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case MSG_START_PRESENTATION:
                startPresentation();
                break;
            }
            super.handleMessage(msg);
        }
    };
    private RenderThread mRenderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.multiple_surfaceview);

        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                mRenderThread.mSurfaceHolder1 = holder;
                mRenderThread.sendSurfaceCreated();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.v(TAG, "width=" + width + ", height = " + height);

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });

        sv = (SurfaceView) findViewById(R.id.surfaceView2);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                mRenderThread.mSurfaceHolder2 = holder;
                mRenderThread.sendSurfaceCreated();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.v(TAG, "width=" + width + ", height = "+height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });

        mRenderThread = new RenderThread();
        mRenderThread.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPresentation();
    }

    void startPresentation() {
        Log.v(TAG, "startPresentation");
        Surface surface = new Surface(mSurfaceTexture);
        mSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                //Log.v(TAG, "onFrameAvailable");
                mRenderThread.requestDraw();
            }
        });

        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        VirtualDisplay vd = dm.createVirtualDisplay("PresentationTest", WIDTH, HEIGHT, DENSITY,
                surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, null, null);

        mPresentation = new DemoPresentation(this, vd.getDisplay());
        mPresentation.show();
        Log.v(TAG, "startPresentation: done");
    }

    void stopPresentation() {
        if (mPresentation != null) {
            mPresentation.dismiss();
            mPresentation = null;
        }
    }

    class RenderThread extends Thread {

        private static final int SIZEOF_FLOAT = 4;
        private FloatBuffer mVertexArray;
        private FloatBuffer mTexCoordArray1;
        private FloatBuffer mTexCoordArray2;
        private int mCoordsPerVertex;
        private int mVertexStride;
        private int mVertexCount;
        private int mTexCoordStride;

        private EglCore mEglCore;
        private int mTextureId;

        private SurfaceHolder mSurfaceHolder1;
        private WindowSurface mWindowSurface1;

        private SurfaceHolder mSurfaceHolder2;
        private WindowSurface mWindowSurface2;

        private RenderHandler mRenderHandler;
        private Texture2dProgram mProgram;

        public RenderThread() {
            final float FULL_RECTANGLE_COORDS[] = {
                    -1.0f, -1.0f,   // 0 bottom left
                    1.0f, -1.0f,   // 1 bottom right
                    -1.0f,  1.0f,   // 2 top left
                    1.0f,  1.0f,   // 3 top right
            };
            final float FULL_RECTANGLE_TEX_COORDS[] = {
                    0.0f, 0.0f,     // 0 bottom left
                    0.5f, 0.0f,     // 1 bottom right
                    0.0f, 1.0f,     // 2 top left
                    0.5f, 1.0f      // 3 top right
            };
            final float FULL_RECTANGLE_TEX_COORDS2[] = {
                    0.5f, 0.0f,     // 0 bottom left
                    1.0f, 0.0f,     // 1 bottom right
                    0.5f, 1.0f,     // 2 top left
                    1.0f, 1.0f      // 3 top right
            };

            mVertexArray = GlUtil.createFloatBuffer(FULL_RECTANGLE_COORDS);
            mTexCoordArray1 = GlUtil.createFloatBuffer(FULL_RECTANGLE_TEX_COORDS);
            mTexCoordArray2 = GlUtil.createFloatBuffer(FULL_RECTANGLE_TEX_COORDS2);
            mCoordsPerVertex = 2;
            mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
            mVertexCount = FULL_RECTANGLE_COORDS.length / mCoordsPerVertex;
            mTexCoordStride = 2 * SIZEOF_FLOAT;
        }

        @Override
        public void run() {
            Looper.prepare();
            mRenderHandler = new RenderHandler(this);
            mEglCore = new EglCore(null, 0);
            Looper.loop();

            Log.d(TAG, "looper quit");
            releaseGl();
            mEglCore.release();
        }

        private void prepareGl() {
            Log.v(TAG, "prepareGl");
            if (mSurfaceTexture != null) {
                Log.v(TAG, "already prepared. ignore");
                return;
            }

            // create EGLSurface for both SurfaceView
            mWindowSurface1 = new WindowSurface(mEglCore, mSurfaceHolder1.getSurface(), false);
            mWindowSurface1.makeCurrent();

            mWindowSurface2 = new WindowSurface(mEglCore, mSurfaceHolder2.getSurface(), false);
            mWindowSurface2.makeCurrent();

            mProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);

            /*
            int[] maxSize = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
            Log.i(TAG, "max texture size = " + maxSize[0]);
            */

            // create texture for Presentation
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GlUtil.checkGlError("glGenTextures");

            int texId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GlUtil.checkGlError("glBindTexture " + texId);

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            mTextureId = texId;
            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurfaceTexture.setDefaultBufferSize(WIDTH, HEIGHT);

            mHandler.sendEmptyMessage(MSG_START_PRESENTATION);
        }

        private void releaseGl() {
            mEglCore.makeNothingCurrent();
        }

        void sendSurfaceCreated() {
            mRenderHandler.sendEmptyMessage(RenderHandler.MSG_SURFACE_CREATED);
        }

        public void requestDraw() {
            mRenderHandler.sendEmptyMessage(RenderHandler.MSG_REQUEST_DRAW);
        }

        public void surfaceCreated() {
            if (mSurfaceHolder1 == null || mSurfaceHolder2 == null) {
                Log.i(TAG, "Not ready");
                return;
            }

            prepareGl();
        }

        public void draw() {
            float[] mat = new float[16];

            mWindowSurface1.makeCurrent();

            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mat);

            mProgram.draw(GlUtil.IDENTITY_MATRIX, mVertexArray, 0, mVertexCount, mCoordsPerVertex, mVertexStride,
                    mat, mTexCoordArray1, mTextureId, mTexCoordStride);
            mWindowSurface1.swapBuffers();

            mWindowSurface2.makeCurrent();
            mProgram.draw(GlUtil.IDENTITY_MATRIX, mVertexArray, 0, mVertexCount, mCoordsPerVertex, mVertexStride,
                    mat, mTexCoordArray2, mTextureId, mTexCoordStride);
            mWindowSurface2.swapBuffers();
        }
    }

    static class RenderHandler extends Handler {
        public static final int MSG_SURFACE_CREATED = 1;
        public static final int MSG_REQUEST_DRAW = 2;
        private final RenderThread mThread;

        RenderHandler(RenderThread thread) {
            mThread = thread;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SURFACE_CREATED:
                    mThread.surfaceCreated();
                    break;
                case MSG_REQUEST_DRAW:
                    mThread.draw();
                    break;
            }
        }
    }
}