package com.rowland.scratchcardreader;

import android.hardware.Camera;

/**
 * Created by Rowland on 4/4/2017.
 */

@SuppressWarnings("deprecation")
public class CameraOld implements CameraSupport {

    private Camera camera;

    @Override
    public CameraOld open(final int cameraId) {
        this.camera = Camera.open(cameraId);
        return this;
    }

    @Override
    public int getOrientation(final int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        return info.orientation;
    }
}