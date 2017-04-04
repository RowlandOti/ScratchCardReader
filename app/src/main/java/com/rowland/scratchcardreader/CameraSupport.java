package com.rowland.scratchcardreader;

import android.annotation.TargetApi;
import android.os.Build;

/**
 * Created by Rowland on 4/4/2017.
 */
public interface CameraSupport {

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    CameraSupport open(int cameraId);

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    int getOrientation(int cameraId);
}
