package net.ossrs.androidpublisher;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;


public class MainActivity extends Activity {
    private Camera camera;
    private Camera.Size vsize;
    private MediaCodec encoder;
    private MediaCodec.BufferInfo ebi;
    private byte[] buffer;
    private long presentationTimeUs;
    private static final String TAG = "SrsPublisher";
    private static final String VCODEC = "video/avc";

    public MainActivity() {
    }

    // for the buffer for YV12(android YUV), @see below:
    // https://developer.android.com/reference/android/hardware/Camera.Parameters.html#setPreviewFormat(int)
    // https://developer.android.com/reference/android/graphics/ImageFormat.html#YV12
    private int getYuvBuffer(int width, int height) {
        // stride = ALIGN(width, 16)
        int stride = (int)Math.ceil(width / 16.0) * 16;
        // y_size = stride * height
        int y_size = stride * height;
        // c_stride = ALIGN(stride/2, 16)
        int c_stride = (int)Math.ceil(width / 32.0) * 16;
        // c_size = c_stride * height/2
        int c_size = c_stride * height / 2;
        // size = y_size + c_size * 2
        return y_size + c_size * 2;
    }

    // choose the right supported color format. @see below:
    // https://developer.android.com/reference/android/media/MediaCodecInfo.html
    // https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities.html
    private int chooseColorFormat() {
        MediaCodecInfo ci = null;

        int nbCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < nbCodecs; i++) {
            MediaCodecInfo mci = MediaCodecList.getCodecInfoAt(i);
            if (!mci.isEncoder()) {
                continue;
            }

            String[] types = mci.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(VCODEC)) {
                    //Log.i(TAG, String.format("encoder %s types: %s", mci.getName(), types[j]));
                    ci = mci;
                    break;
                }
            }
        }

        int matchedColorFormat = 0;
        MediaCodecInfo.CodecCapabilities cc = ci.getCapabilitiesForType(VCODEC);
        for (int i = 0; i < cc.colorFormats.length; i++) {
            int cf = cc.colorFormats[i];
            //Log.i(TAG, String.format("encoder %s supports color fomart %d", ci.getName(), cf));

            // choose YUV for h.264, prefer the bigger one.
            if (cf >= cc.COLOR_FormatYUV411Planar && cf <= cc.COLOR_FormatYUV422SemiPlanar) {
                if (cf > matchedColorFormat) {
                    matchedColorFormat = cf;
                }
            }
        }

        Log.i(TAG, String.format("encoder %s choose color format %d", ci.getName(), matchedColorFormat));
        return matchedColorFormat;
    }

    // when got encoded h264 es stream.
    private void onEncodedFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        Log.i(TAG, String.format("encoded frame %dB, offset=%d pts=%dms", bi.size, bi.offset, bi.presentationTimeUs / 1000));
        //StringBuilder sb = new StringBuilder();
        //for (int i = 0; i < bi.size; i++) {
        //    sb.append(String.format("0x%s ", Integer.toHexString(es.get(i) & 0xFF)));
        //}
        //Log.i(TAG, String.format("dumps the es stream:\n%s", sb.toString()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // when got YUV frame from camera.
        // @see https://developer.android.com/reference/android/media/MediaCodec.html
        final Camera.PreviewCallback onYuvFrame = new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                //Log.i(TAG, String.format("got YUV image, size=%d", data.length));

                // feed the encoder with yuv frame, got the encoded 264 es stream.
                ByteBuffer[] inBuffers = encoder.getInputBuffers();
                ByteBuffer[] outBuffers = encoder.getOutputBuffers();
                if (true) {
                    int inBufferIndex = encoder.dequeueInputBuffer(-1);
                    //Log.i(TAG, String.format("try to dequeue input buffer, ii=%d", inBufferIndex));
                    if (inBufferIndex >= 0) {
                        ByteBuffer bb = inBuffers[inBufferIndex];
                        bb.clear();
                        bb.put(data, 0, data.length);
                        long pts = new Date().getTime() * 1000 - presentationTimeUs;
                        //Log.i(TAG, String.format("feed YUV to encode %dB, pts=%d", data.length, pts / 1000));
                        encoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
                    }

                    for (;;) {
                        int outBufferIndex = encoder.dequeueOutputBuffer(ebi, 0);
                        //Log.i(TAG, String.format("try to dequeue output buffer, ii=%d, oi=%d", inBufferIndex, outBufferIndex));
                        if (outBufferIndex >= 0) {
                            ByteBuffer bb = outBuffers[outBufferIndex];
                            onEncodedFrame(bb, ebi);
                            encoder.releaseOutputBuffer(outBufferIndex, false);
                        }

                        if (outBufferIndex < 0) {
                            break;
                        }
                    }
                }

                // to fetch next frame.
                camera.addCallbackBuffer(buffer);
            }
        };

        // for camera, @see https://developer.android.com/reference/android/hardware/Camera.html
        final Button btn = (Button)findViewById(R.id.capture);
        final SurfaceView preview = (SurfaceView)findViewById(R.id.camera_preview);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                camera = Camera.open();
                Camera.Parameters parameters = camera.getParameters();

                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
                parameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                parameters.setPreviewFormat(ImageFormat.YV12);

                Camera.Size size = null;
                List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
                for (int i = 0; i < sizes.size(); i++) {
                    //Log.i(TAG, String.format("camera supported picture size %dx%d", sizes.get(i).width, sizes.get(i).height));
                    if (sizes.get(i).width == 640) {
                        size = sizes.get(i);
                    }
                }
                parameters.setPictureSize(size.width, size.height);
                Log.i(TAG, String.format("set the picture size in %dx%d", size.width, size.height));

                sizes = parameters.getSupportedPreviewSizes();
                for (int i = 0; i < sizes.size(); i++) {
                    //Log.i(TAG, String.format("camera supported preview size %dx%d", sizes.get(i).width, sizes.get(i).height));
                    if (sizes.get(i).width == 640) {
                        vsize = size = sizes.get(i);
                    }
                }
                parameters.setPreviewSize(size.width, size.height);
                Log.i(TAG, String.format("set the preview size in %dx%d", size.width, size.height));

                camera.setParameters(parameters);

                // set the callback and start the preview.
                buffer = new byte[getYuvBuffer(size.width, size.height)];
                camera.addCallbackBuffer(buffer);
                camera.setPreviewCallbackWithBuffer(onYuvFrame);
                try {
                    camera.setPreviewDisplay(preview.getHolder());
                } catch (IOException e) {
                    Log.e(TAG, "preview video failed.");
                    e.printStackTrace();
                    return;
                }
                Log.i(TAG, String.format("start to preview video in %dx%d, buffer %dB", size.width, size.height, buffer.length));
                camera.startPreview();

                // encoder yuv to 264 es stream.
                // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
                try {
                    encoder = MediaCodec.createEncoderByType(VCODEC);
                } catch (IOException e) {
                    Log.e(TAG, "create encoder failed.");
                    e.printStackTrace();
                    return;
                }
                ebi = new MediaCodec.BufferInfo();
                presentationTimeUs = new Date().getTime() * 1000;

                // start the encoder.
                // @see https://developer.android.com/reference/android/media/MediaCodec.html
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vsize.width, vsize.height);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 125000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, chooseColorFormat());
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                Log.i(TAG, "encoder start");

                btn.setEnabled(false);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (encoder != null) {
            Log.i(TAG, "stop encoder");
            encoder.stop();
            encoder.release();
        }

        if (camera != null) {
            Log.i(TAG, "stop preview");
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}