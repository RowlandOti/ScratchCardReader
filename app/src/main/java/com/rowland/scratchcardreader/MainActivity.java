package com.rowland.scratchcardreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.karumi.dexter.Dexter;
import com.karumi.dexter.MultiplePermissionsReport;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.multi.MultiplePermissionsListener;

import java.io.File;
import java.util.List;

/**
 * This is the main activity (duh). Check Preview.java since that is where all the cool stuff is done. But do remember that everything starts here
 * <p>
 * "I'm trying to free your mind, Neo, but I can only show you the door. You're the one that has to walk through it."
 *
 * @author Jason Rogena
 */
public class MainActivity extends Activity implements OnClickListener {
    public static final String DATA_PATH = Environment.getExternalStorageDirectory() + File.separator + "SCR" + File.separator;

    private CameraPreview cameraPreview = null;
    private Camera camera;
    private FrameLayout previewFrameLayout;
    private ImageView focusAreaImg, lastImageIV;
    private RelativeLayout mainLayout;
    private View upperLimit, lowerLimit, leftLimit, rightLimit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //requestWindowFeature(Window.FEATURE_NO_TITLE);
        //getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        focusAreaImg = (ImageView) this.findViewById(R.id.focus_area_img);
        mainLayout = (RelativeLayout) findViewById(R.id.main_layout);
        upperLimit = (View) findViewById(R.id.upper_limit);
        lowerLimit = (View) findViewById(R.id.lower_limit);
        leftLimit = (View) findViewById(R.id.left_limit);
        rightLimit = (View) findViewById(R.id.right_limit);
        lastImageIV = (ImageView) findViewById(R.id.last_image);
        previewFrameLayout = (FrameLayout) findViewById(R.id.preview);

        Dexter.withActivity(this)
                .withPermissions(
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_PHONE_STATE
                ).withListener(new MultiplePermissionsListener() {
            @Override
            public void onPermissionsChecked(MultiplePermissionsReport report) {
                /* ... */
                if (report.areAllPermissionsGranted()) {
                    if (cameraPreview == null) {
                        cameraPreview = new CameraPreview(MainActivity.this, mainLayout, previewFrameLayout, upperLimit, lowerLimit, leftLimit, rightLimit, lastImageIV);
                        previewFrameLayout.addView(cameraPreview);
                        previewFrameLayout.setOnClickListener(MainActivity.this);
                        //SampleSender sampleSender =new SampleSender();
                        //sampleSender.execute(this);
                    } else {
                        cameraPreview.resume();
                    }
                }

            }

            @Override
            public void onPermissionRationaleShouldBeShown(List<PermissionRequest> permissions, PermissionToken token) {
				/* ... */
            }
        }).check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopService(new Intent(this, SenderService.class));


    }


    @Override
    protected void onPause() {
        //cameraPreview.pause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        startService(new Intent(this, SenderService.class));
    }

    @Override
    public void onClick(View v) {
        if (v == previewFrameLayout) {
            if (focusAreaImg.getVisibility() == ImageView.VISIBLE) {
                focusAreaImg.setVisibility(ImageView.GONE);
            }
            cameraPreview.autoFocus();
        }
    }

}
