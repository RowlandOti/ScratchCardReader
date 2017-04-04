package com.rowland.scratchcardreader;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;

import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.ShutterCallback;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author Jason Rogena
 *         <p>
 *         As of now, this is the brain of the project. Tesseract recognition done here, Autofocus management also done here
 */
class CameraPreview extends SurfaceView implements SurfaceHolder.Callback, View.OnLongClickListener, View.OnTouchListener {
    private static final String TAG = "Preview";

    SurfaceHolder mHolder;
    public Camera camera;
    private int heightOfCamera = -1;
    AutoFocusCallback autoFocusCallback;
    private ShutterCallback shutterCallback;
    private PictureCallback rawCallback;
    private PictureCallback jpegCallback;
    private double activeImageHeight = 0.1;//ration of active height to total image height
    private double activeImageWidth = 0.8;//ration of active width to total image width
    private int previewHeight = -1;
    private int pictureHeight = -1;
    private int previewWidth = -1;
    private int pictureWidth = -1;
    private boolean idle = true;
    private RelativeLayout mainLayout;
    private View upperLimit;
    private View lowerLimit;
    private View leftLimit;
    private View rightLimit;
    private FrameLayout previewFrameLayout;
    private boolean canResizeFlag = false;
    private int motionDownY;
    private int motionX;
    private CountDownTimer countDownTimer;
    private ImageView lastImageIV;
    private AmbientLightManager ambientLightManager;
    private PreferenceHandler preferenceHandler;
    private MainActivity mainActivity;

    CameraPreview(MainActivity mainActivity, RelativeLayout mainLayout, FrameLayout previewFrameLayout, View upperLimit, View lowerLimit, View leftLimit, View rightLimit, ImageView lastImageIV) {
        super(mainActivity);

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        preferenceHandler = new PreferenceHandler(mainActivity);
        loadActiveImageSize(true);

        String appCrushed = preferenceHandler.getPreference(PreferenceHandler.KEY_APP_CRASHED);
        if (appCrushed != null && appCrushed.equals(PreferenceHandler.VALUE_TRUE)) {
            Toast.makeText(mainActivity, "App appeared to have crashed the last time :(.. Fixing myself!", Toast.LENGTH_LONG).show();
            int bestPreviewRatio = Integer.parseInt(preferenceHandler.getPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO));
            if (bestPreviewRatio > 1) bestPreviewRatio--;
            Log.d("CAMERA", "Resetting bestPreviewRatio to " + bestPreviewRatio);
            preferenceHandler.setPreference(PreferenceHandler.KEY_AVOID_INCREASING_BPR, PreferenceHandler.VALUE_TRUE);
            preferenceHandler.setPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO, String.valueOf(bestPreviewRatio));
        }

        this.mainActivity = mainActivity;
        this.mainLayout = mainLayout;
        this.upperLimit = upperLimit;
        this.upperLimit.setOnLongClickListener(this);
        this.upperLimit.setOnTouchListener(this);
        this.lowerLimit = lowerLimit;
        this.lowerLimit.setOnLongClickListener(this);
        this.lowerLimit.setOnTouchListener(this);
        this.leftLimit = leftLimit;
        this.leftLimit.setOnTouchListener(this);
        this.rightLimit = rightLimit;
        this.rightLimit.setOnTouchListener(this);
        this.lastImageIV = lastImageIV;
        this.previewFrameLayout = previewFrameLayout;
        mHolder = getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        //get active camera width and height from shared preferences

        ambientLightManager = new AmbientLightManager(mainActivity);

        shutterCallback = new ShutterCallback() {

            @Override
            public void onShutter() {
                Log.d("CAMERA", "shutter called");

            }
        };
        rawCallback = new PictureCallback() {

            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                Log.d("CAMERA", "Picture taken");
            }
        };
        jpegCallback = new PictureCallback() {

            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                try {
                    if (idle) {
                        Log.d("CAMERA", "OCR thread was idle");
                        idle = false;
                        OCRHandler handler = new OCRHandler();
                        handler.execute(data);
                    } else
                        Log.d("CAMERA", "OCR thread is not idle");
                    camera.startPreview();

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                }
                Log.d("CAMERA", "onPictureTaken - jpeg");

            }
        };

        autoFocusCallback = new AutoFocusCallback() {

            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                if (success) {
                    camera.takePicture(shutterCallback, null, jpegCallback);
                } else {
                    autoFocus();//make sure the endless loop doesnt end
                }
            }
        };
    }

    /**
     * This method should be as clean as possible. It can be the source of very many random app crashes
     */
    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        camera = Camera.open();
        Log.d("CAMERA", "camera opened");
        try {
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(new PreviewCallback() {

                public void onPreviewFrame(byte[] data, Camera arg1) {
                    CameraPreview.this.invalidate();
                }
            });
            ambientLightManager.startMonitoring(this);
        } catch (IOException e) {
            Toast.makeText(this.getContext(), "Bummer, the app was unable to gain access exclusive access to the camera", Toast.LENGTH_LONG).show();
            e.printStackTrace();
            mainActivity.finish();
        } catch (RuntimeException e) {
            Toast.makeText(this.getContext(), "WTF, that's embarrassing. The app will be unable to proceed normally beyond this point. I'm working on that", Toast.LENGTH_LONG).show();
            e.printStackTrace();
            mainActivity.finish();
        }
    }

    /**
     * This method should be as clean as possible.
     * - ensure the camera resource is released gracefully
     * - ensure the instance of the camera variable is released by making it null
     * If this is not done a lot of app crashing will occure
     */
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
        // Because the CameraDevice object is not a shared resource, it's very
        // important to release it when the activity is paused.
        Log.d("CAMERA", "Surface destroyed called");
        ambientLightManager.stopMonitoring();
        camera.stopPreview();
        camera.setPreviewCallback(null);
        camera.release();
        camera = null;
    }

    /**
     * This method is called when the app is pause.
     * Ensure you stop the camera from taking previews otherwise the app will continue consuming battery when in the paused state
     */
    public void pause() {
        if (camera != null) {
            camera.stopPreview();
        }
    }

    /**
     * Called when the app is unpaused
     * resume anything you paused in pause()
     */
    public void resume() {
        if (camera != null) {
            camera.startPreview();
        }
    }


    /**
     * Setting of camera parameters done here (Including setting the preview and picture sizes)
     */
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Now that the size is known, set up the camera parameters and begin
        // the preview.
        Camera.Parameters parameters = camera.getParameters();

        List<Camera.Size> pictureSizes = parameters.getSupportedPictureSizes();
        int pictureHeight = 0;
        int pictureWidth = 0;
        for (int i = 0; i < pictureSizes.size(); i++) {
            int pictureSize = pictureHeight * pictureWidth;
            int newPictureSize = pictureSizes.get(i).width * pictureSizes.get(i).height;
            if (newPictureSize > pictureSize) {
                pictureWidth = pictureSizes.get(i).width;
                pictureHeight = pictureSizes.get(i).height;
            }
        }

        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        List<SizeChecker> sizeChecker = new ArrayList<CameraPreview.SizeChecker>();
        double pictureARatio = ((double) pictureWidth) / ((double) pictureHeight);

        int previewWidth = 0;
        int previewHeight = 0;
        for (int i = 0; i < previewSizes.size(); i++) {
            double thisARatio = ((double) previewSizes.get(i).width) / ((double) previewSizes.get(i).height);
            int size = previewSizes.get(i).width * previewSizes.get(i).height;
            double ratioDifference = Math.abs(pictureARatio - thisARatio);
            Log.d("CAMERA", "ratio diff : " + String.valueOf(ratioDifference));
            sizeChecker.add(new SizeChecker(ratioDifference, i, size));
        }

        Collections.sort(sizeChecker, new Comparator<SizeChecker>() {
            @Override
            public int compare(SizeChecker a, SizeChecker b) {
                if (b.getSize() < a.getSize()) {
                    return -1;
                } else if (b.getSize() > a.getSize()) {
                    return 1;
                }
                return 0;
            }

        });
        //get the preview size index with the least aspect ratio difference
        int preferedPrefIndex = -1;
        double leastARatio = 0;
        for (int i = 0; i < sizeChecker.size(); i++) {
            //Log.d("CAMERA", "Index  = "+String.valueOf(i));
            if (i == 0) {
                leastARatio = sizeChecker.get(i).getRatioDiff();
            }

            if (sizeChecker.get(i).getRatioDiff() == 0) {
                preferedPrefIndex = sizeChecker.get(i).getIndex();
                leastARatio = sizeChecker.get(i).getRatioDiff();
                break;
            } else if (sizeChecker.get(i).getRatioDiff() < leastARatio) {
                leastARatio = sizeChecker.get(i).getRatioDiff();
                preferedPrefIndex = sizeChecker.get(i).getIndex();
            }
            Log.d("CAMERA", "least a ratio is :" + String.valueOf(leastARatio));
        }
        if (preferedPrefIndex != -1) {
            previewHeight = previewSizes.get(preferedPrefIndex).height;
            previewWidth = previewSizes.get(preferedPrefIndex).width;
        }

        this.previewHeight = previewWidth;
        this.pictureHeight = pictureWidth;
        this.previewWidth = previewHeight;
        this.pictureWidth = pictureHeight;
        Log.d("CAMERA", "Focal length : " + String.valueOf(parameters.getFocalLength()));
        parameters.setPreviewSize(previewWidth, previewHeight);
        parameters.setPictureSize(pictureWidth, pictureHeight);
        parameters.setPictureFormat(ImageFormat.JPEG);
        parameters.setJpegQuality(100);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_MACRO);
        parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);

        Log.d("CAMERA", "layoutSize :" + String.valueOf(previewFrameLayout.getHeight()) + " x " + String.valueOf(previewFrameLayout.getWidth()));
        Log.d("CAMERA", "preview size :" + String.valueOf(previewWidth) + " x " + String.valueOf(previewHeight));
        Log.d("CAMERA", "picture size :" + String.valueOf(pictureWidth) + " x " + String.valueOf(pictureHeight));
        heightOfCamera = previewWidth;
        try {
            camera.setParameters(parameters);
            camera.setDisplayOrientation(90);
            camera.startPreview();
            resetYLimits();
            resetXLimits();
        } catch (RuntimeException e) {
            Toast.makeText(this.getContext(), "Bummer! Your phone appears to be incompatible with this app. But not for long", Toast.LENGTH_LONG).show();
            e.printStackTrace();
            mainActivity.finish();
        }
    }

    /**
     * This method resets the height of the active image area using the activeImageHeight
     */
    private void resetYLimits() {
        if (activeImageHeight < 0) activeImageHeight = 0;
        else if (activeImageHeight > 0.8) activeImageHeight = 0.8;
        //Log.d("CAMERA", "Active main layout height ratio = "+String.valueOf(activeImageHeight));
        int activeHeight = (int) ((double) previewFrameLayout.getHeight() * activeImageHeight);
        //Log.d("CAMERA", "active main layout height :"+String.valueOf(activeHeight));
        int limitHeight = (previewFrameLayout.getHeight() - activeHeight) / 2;
        LayoutParams upperLimitLP = upperLimit.getLayoutParams();
        upperLimitLP.height = limitHeight;
        upperLimit.setLayoutParams(upperLimitLP);
        LayoutParams lowerLimitLP = lowerLimit.getLayoutParams();
        lowerLimitLP.height = limitHeight;
        lowerLimit.setLayoutParams(lowerLimitLP);
        LayoutParams leftLimitLP = leftLimit.getLayoutParams();
        leftLimitLP.height = activeHeight + 1;
        leftLimit.setLayoutParams(leftLimitLP);
        LayoutParams rightLimitLP = rightLimit.getLayoutParams();
        rightLimitLP.height = activeHeight + 1;
        rightLimit.setLayoutParams(rightLimitLP);
    }

    /**
     * This method resets the width of the active image area using the activeImageWidth
     */
    private void resetXLimits() {
        if (activeImageWidth < 0) activeImageWidth = 0;
        else if (activeImageWidth > 0.9) activeImageWidth = 0.9;

        int activeWidth = (int) ((double) previewFrameLayout.getWidth() * activeImageWidth);
        int limitWidth = (previewFrameLayout.getWidth() - activeWidth) / 2;
        LayoutParams leftLimitLP = leftLimit.getLayoutParams();
        leftLimitLP.width = limitWidth;
        leftLimit.setLayoutParams(leftLimitLP);
        LayoutParams rightLimitLP = rightLimit.getLayoutParams();
        rightLimitLP.width = limitWidth;
        rightLimit.setLayoutParams(rightLimitLP);
    }

    /**
     * This method retrieves the activeImageHeight and activeImageWidth ratios saved in shared preferences
     *
     * @param setIfNotSet - if set to true, the activeImageHeight and activeImageWidth ratios will be saved in shared preferences if there is no saved copy of the ratios in shared preferences
     */
    private void loadActiveImageSize(boolean setIfNotSet) {
        if (preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT) != null) {
            this.activeImageHeight = Double.parseDouble(preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT));
        }
        if (preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH) != null) {
            this.activeImageWidth = Double.parseDouble(preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH));
        }
        if (setIfNotSet) {
            if (preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT) == null || preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH) == null) {
                saveActiveImageSize();
            }
        }
    }

    /**
     * This method saves the activeImageHeight and activeImageWidth ratios in shared preferences
     */
    private void saveActiveImageSize() {
        if (preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT) == null) {
            preferenceHandler.setPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT, String.valueOf(this.activeImageHeight));
        } else {
            double savedAIH = Double.parseDouble(preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT));
            double averageAIH = (savedAIH + this.activeImageHeight) / 2;
            preferenceHandler.setPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_HEIGHT, String.valueOf(averageAIH));
        }
        if (preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH) == null) {
            preferenceHandler.setPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH, String.valueOf(this.activeImageWidth));
        } else {
            double savedAIW = Double.parseDouble(preferenceHandler.getPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH));
            double averageAIW = (savedAIW + this.activeImageWidth) / 2;
            preferenceHandler.setPreference(PreferenceHandler.KEY_ACTIVE_CAMERA_WIDTH, String.valueOf(averageAIW));
        }
        preferenceHandler.setPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO, "1");
        preferenceHandler.setPreference(PreferenceHandler.KEY_AVOID_INCREASING_BPR, PreferenceHandler.VALUE_FALSE);
    }

    /**
     * This method initializes camera autofocus
     */
    public void autoFocus() {
        if (camera != null) {
            //COMMENTED CODE REQUIRES API LEVEL 14
            /*double yCompensation = (previewFrameLayout.getHeight() - previewHeight) / previewFrameLayout.getHeight();
			double activeImageHeight = Preview.this.activeImageHeight - yCompensation;
			
			double xCompensation = (previewFrameLayout.getWidth() - previewWidth) / previewFrameLayout.getWidth();
			double activeImageWidth = Preview.this.activeImageWidth - xCompensation;
			
			int halfWidth = (int) ((2000 * activeImageWidth)/2);
			int halfHeight = (int) ((2000 * activeImageHeight)/2);
			
			Parameters parameters = camera.getParameters();
			Area centralFocusArea = new Area(new Rect(-1*halfHeight, -1*halfWidth, halfHeight, 1000), halfWidth);
			//Area centralFocusArea = new Area(new Rect(-1000, -3, 1000, 3), 1000);
			List<Area> focusAreas = new ArrayList<Camera.Area>();
			List<Area> meteringAreas = new ArrayList<Camera.Area>();
			focusAreas.add(centralFocusArea);
			meteringAreas.add(centralFocusArea);
			
			parameters.setFocusAreas(focusAreas);
			parameters.setMeteringAreas(meteringAreas);
			camera.setParameters(parameters);*/
            try {
                camera.autoFocus(autoFocusCallback);
            } catch (RuntimeException e) {
                Toast.makeText(this.getContext(), "WOW, go slow on the clicking there Tiger", Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }
    }

    public int getHeightOfCamera() {
        return heightOfCamera;
    }

    /**
     * This method turns the camera flashlight either on or off. Camera whitbalance is also reset
     *
     * @param toOn - if true, flashlights is set to on
     */
    public void setTorch(boolean toOn) {
        if (camera != null) {
            Parameters parameters = camera.getParameters();
            if (toOn) {
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_FLUORESCENT);
            } else {
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            }
            camera.setParameters(parameters);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        Paint p = new Paint(Color.RED);
        //Log.d(TAG, "draw");
        canvas.drawText("PREVIEW", canvas.getWidth() / 2,
                canvas.getHeight() / 2, p);
    }

    /**
     * This async thread does everything related to Tesseract recognition
     *
     * @author jason
     */
    private class OCRHandler extends AsyncTask<byte[], Integer, String> {
        private Bitmap lastCapture;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            preferenceHandler.setPreference(PreferenceHandler.KEY_APP_CRASHED, PreferenceHandler.VALUE_TRUE);
            Toast.makeText(CameraPreview.this.getContext(), "starting..", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            Toast.makeText(CameraPreview.this.getContext(), "still working..", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected String doInBackground(byte[]... data) {
            try {
                //FileOutputStream outStream = new FileOutputStream(String.format(MainActivity.DATA_PATH+"%d.jpg", System.currentTimeMillis()));
                double yCompensation = (previewFrameLayout.getHeight() - previewHeight) / previewFrameLayout.getHeight();
                double activeImageHeight = CameraPreview.this.activeImageHeight - yCompensation;

                double xCompensation = (previewFrameLayout.getWidth() - previewWidth) / previewFrameLayout.getWidth();
                double activeImageWidth = CameraPreview.this.activeImageWidth - xCompensation;

                Log.d("CAMERA", "byte size = " + String.valueOf(data[0].length));
                // First decode with inJustDecodeBounds=true to check dimensions
                BitmapFactory.Options bitMapOptions = new BitmapFactory.Options();
                bitMapOptions.inJustDecodeBounds = true;
                //bitMapOptions.inPurgeable = true; //helps avoid OutOfMemoryErrors
                //consider using bitMapOptions.inJustDecodeBounds = true
                Bitmap bitmap = BitmapFactory.decodeByteArray(data[0], 0, data[0].length, bitMapOptions);

                // Calculate inSampleSize
                bitMapOptions.inSampleSize = calculateInSampleSize(bitMapOptions, previewHeight, previewWidth);
                bitMapOptions.inJustDecodeBounds = false;

                //load a scaled down version of the bitmap
                bitmap = BitmapFactory.decodeByteArray(data[0], 0, data[0].length, bitMapOptions);
                Matrix matrix = new Matrix();
                matrix.postRotate(90);
                Bitmap rotatedImage = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                int halfHeight = (int) rotatedImage.getHeight() / 2;
                int croppedHeight = (int) (rotatedImage.getHeight() * activeImageHeight);
                int y = halfHeight - (int) (croppedHeight / 2);

                int halfWidth = (int) rotatedImage.getWidth() / 2;
                int croppedWidth = (int) (rotatedImage.getWidth() * activeImageWidth);
                int x = halfWidth - (int) (croppedWidth / 2);

                Bitmap croppedImage = Bitmap.createBitmap(rotatedImage, x, y, croppedWidth, croppedHeight);
                //croppedImage.compress(CompressFormat.JPEG, 100, outStream);
                //outStream.close();
                Log.d("CAMERA", "cropped image width = " + String.valueOf(croppedImage.getWidth()));
                Log.d("CAMERA", "location of root is " + MainActivity.DATA_PATH);
                if (!(new File(MainActivity.DATA_PATH + "tessdata" + File.separator + "eng.traineddata")).exists()) {
                    try {
                        Log.d("CAMERA", "copying eng to file system");
                        File tessDir = new File(MainActivity.DATA_PATH + "tessdata");
                        tessDir.mkdirs();
                        AssetManager assetManager = CameraPreview.this.getContext().getAssets();
                        String engTessPath = "tessdata" + File.separator + "eng.traineddata";
                        InputStream in = assetManager.open(engTessPath);
                        //GZIPInputStream gin = new GZIPInputStream(in);
                        OutputStream out = new FileOutputStream(MainActivity.DATA_PATH + engTessPath);
                        // Transfer bytes from in to out
                        byte[] buf = new byte[1024];
                        int len;
                        //while ((lenf = gin.read(buff)) > 0) {
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        in.close();
                        //gin.close();
                        out.close();

                        Log.d("CAMERA", "Finished copying eng");
                    } catch (IOException e) {
                        Log.e("CAMERA", "Was unable to copy eng traineddata " + e.toString());
                    }
                } else {
                    Log.d("CAMERA", "eng already on sd");
                }
                Log.d("CAMERA", "Calling TessBaseAPI");
                TessBaseAPI baseAPI = new TessBaseAPI();
                baseAPI.setDebug(true);
                Log.d("CAMERA", "1");
                baseAPI.init(MainActivity.DATA_PATH, "eng");
                Log.d("CAMERA", "2");
                baseAPI.setImage(croppedImage);
                lastCapture = croppedImage;
                Log.d("CAMERA", "3");
                String result = baseAPI.getUTF8Text();
                Log.d("CAMERA", "4");
                baseAPI.end();
                Log.d("CAMERA", "TessBaseAPI finished analyzing");

                //result=result.replaceAll("\\s", "");//remove all whitespaces
                //result=result.replaceAll("o", "0");//sometimes 0s are identified as os by the ocr lib
                result = result.replaceAll("[^0123456789]", "");//remove all none numbers

                return result;

            } catch (Exception e) {
                e.printStackTrace();
                System.err.print(e.getMessage());
            } finally {
            }
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            preferenceHandler.setPreference(PreferenceHandler.KEY_APP_CRASHED, PreferenceHandler.VALUE_FALSE);
            if (result != null) {
                Log.d("CAMERA", result);
                TelephonyManager telephonyManager = ((TelephonyManager) CameraPreview.this.getContext().getSystemService(Context.TELEPHONY_SERVICE));
                String operatorName = telephonyManager.getNetworkOperatorName();
                Log.d("CAMERA", "operator = " + operatorName);
                showLastCapture(lastCapture, operatorName, result);

                if (result.length() > 12 && operatorName.trim().equals("Safaricom") && result.length() < 20) {
                    lastImageIV.setVisibility(ImageView.GONE);
                    saveActiveImageSize();
                    //Toast.makeText(Preview.this.getContext(), "on Safaricom "+result, Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Intent.ACTION_DIAL);
                    intent.setData(Uri.fromParts("tel", "*141*" + result + "#", "#"));
                    CameraPreview.this.getContext().startActivity(intent);
                } else if (operatorName.trim().equals("Safaricom")) {
                    increaseImageQuality();

                    if (result.length() <= 12) {
                        if (activeImageHeight < 0.05) {
                            Toast.makeText(CameraPreview.this.getContext(), "Try increasing the size of the active area", Toast.LENGTH_LONG).show();
                        }
                    } else if (operatorName.trim().equals("Safaricom") && result.length() >= 20) {
                        if (activeImageHeight > 0.15) {
                            Toast.makeText(CameraPreview.this.getContext(), "Try reducing the size of the active area", Toast.LENGTH_LONG).show();
                        }
                    }

                } else {
                    Toast.makeText(CameraPreview.this.getContext(), "Coming soon to " + operatorName, Toast.LENGTH_LONG).show();
                }

            }
            idle = true;
            autoFocus();
        }
    }

    @Override
    public boolean onLongClick(View v) {
		/*if(v == upperLimit || v == lowerLimit) {
			canResizeFlag = true;
			return true;
		}*/
        return false;
    }


    /**
     * This method changes the values of activeImageHeight and activeImageWidth using the x and y coords of a touch
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_CANCEL || event.getAction() == MotionEvent.ACTION_UP) {
            canResizeFlag = false;
        } else if (event.getAction() == MotionEvent.ACTION_DOWN) {
            canResizeFlag = true;
            if (v == upperLimit || v == lowerLimit) {
                motionDownY = (int) event.getRawY();
            } else if (v == rightLimit || v == leftLimit) {
                motionX = (int) event.getRawX();
            }
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (canResizeFlag) {
                if (v == upperLimit || v == lowerLimit) {
                    int nowY = (int) event.getRawY();
                    boolean increaseActiveHeight = false;
                    if (v == upperLimit && motionDownY > nowY) {
                        increaseActiveHeight = true;
                    } else if (v == lowerLimit && motionDownY < nowY) {
                        increaseActiveHeight = true;
                    }
                    int diff = Math.abs(nowY - motionDownY);
                    double effect = diff * 0.0025;
                    if (increaseActiveHeight) {
                        if ((activeImageHeight + effect) < 1)
                            activeImageHeight = activeImageHeight + effect;
                    } else {
                        if ((activeImageHeight - effect) > 0)
                            activeImageHeight = activeImageHeight - effect;
                    }
                    resetYLimits();
                    motionDownY = nowY;
                } else if (v == rightLimit || v == leftLimit) {
                    int nowX = (int) event.getRawX();
                    boolean increaseActiveWidth = false;
                    if (v == leftLimit && motionX > nowX) {
                        increaseActiveWidth = true;
                    } else if (v == rightLimit && motionX < nowX) {
                        increaseActiveWidth = true;
                    }
                    int diff = Math.abs(nowX - motionX);
                    double effect = diff * 0.0025;
                    if (increaseActiveWidth) {
                        if ((activeImageWidth + effect) < 1)
                            activeImageWidth = activeImageWidth + effect;
                    } else {
                        if ((activeImageWidth - effect) > 0)
                            activeImageWidth = activeImageWidth - effect;
                    }
                    resetXLimits();
                    motionX = nowX;
                }
            }
        }
        return true;
    }

    /**
     * This calculates a sample size value that is a power of two based on a target width and height
     *
     * @param options   BitmapFactory.Options for the bitmap
     * @param reqWidth  The width you want the bitmap to scale down to
     * @param reqHeight The height you want the bitmap to scale down to
     * @return The appropriate inSampleSize
     */
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        reqWidth = reqWidth * Integer.parseInt(preferenceHandler.getPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO));
        reqHeight = reqHeight * Integer.parseInt(preferenceHandler.getPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO));
        int inSampleSize = 1;
        final int height = options.outHeight;
        final int width = options.outWidth;

        Log.d("CAMERA", "*** required height = " + String.valueOf(reqHeight) + " and actual height = " + String.valueOf(height));
        Log.d("CAMERA", "*** required width = " + String.valueOf(reqWidth) + " and actual width = " + String.valueOf(width));

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidht = width / 2;
            while ((halfHeight / inSampleSize) > reqHeight && (halfWidht / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }
        Log.d("CAMERA", "*** inSampleSize = " + String.valueOf(inSampleSize));
        return inSampleSize;
    }

    private class SizeChecker {
        private double ratioDiff;
        private int index;
        private int size;//sort using this

        public SizeChecker(double ratioDiff, int index, int size) {
            this.ratioDiff = ratioDiff;
            this.index = index;
            this.size = size;
        }

        public double getRatioDiff() {
            return ratioDiff;
        }

        public int getIndex() {
            return index;
        }

        public int getSize() {
            return size;
        }
    }

    private void increaseImageQuality() {
        int bestPreviewRatio = Integer.parseInt(preferenceHandler.getPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO));
        bestPreviewRatio++;
        if (previewHeight * bestPreviewRatio <= pictureHeight) {
            String avoidIncreasingBPR = preferenceHandler.getPreference(PreferenceHandler.KEY_AVOID_INCREASING_BPR);
            if (avoidIncreasingBPR != null && avoidIncreasingBPR.equals(PreferenceHandler.VALUE_FALSE)) {
                Log.d("CAMERA", "Increasing bestPreviewRatio to " + String.valueOf(bestPreviewRatio));
                preferenceHandler.setPreference(PreferenceHandler.KEY_BEST_PREVIEW_RATIO, String.valueOf(bestPreviewRatio));
            } else if (avoidIncreasingBPR != null && avoidIncreasingBPR.equals(PreferenceHandler.VALUE_TRUE))
                Log.d("CAMERA", "Cannot increase bestPreviewRatio to avoid app from crushing");
        } else {
            Log.d("CAMERA", "Cannot increase bestPreviewRatio to " + String.valueOf(bestPreviewRatio));
        }
    }

    private void showLastCapture(Bitmap lastCapture, String operatorName, String result) {
        //Show captured image
        if (countDownTimer == null) {
            countDownTimer = new CountDownTimer(750, 750) {

                @Override
                public void onTick(long millisUntilFinished) {
                    // TODO Auto-generated method stub

                }

                @Override
                public void onFinish() {
                    lastImageIV.setVisibility(ImageView.GONE);
                }
            };
        }
        lastImageIV.setImageBitmap(lastCapture);
        lastImageIV.setVisibility(ImageView.VISIBLE);
        countDownTimer.start();

        //save image
        SampleSaver sampleSaver = new SampleSaver(operatorName.trim(), result.length());
        sampleSaver.execute(lastCapture);
    }
}