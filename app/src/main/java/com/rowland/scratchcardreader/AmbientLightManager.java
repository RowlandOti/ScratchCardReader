package com.rowland.scratchcardreader;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

public class AmbientLightManager implements SensorEventListener {
	private static final float TOO_DARK_LUX = 45.0f;
	private static final float BRIGHT_ENOUGH_LUX = 450.0f;
	
	private final Context context;
	private Sensor lightSensor;
	private CameraPreview cameraPreview;
	
	public AmbientLightManager(Context context) {
		this.context = context;
	}
	
	public void startMonitoring(CameraPreview cameraPreview) {
		this.cameraPreview = cameraPreview;
		SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
		if(lightSensor != null) {
			sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
		}
	}
	
	public void stopMonitoring() {
		if(lightSensor!=null){
			 SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		     sensorManager.unregisterListener(this);
		     cameraPreview = null;
		     lightSensor = null;
		}
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		//PSSHH!
	}

	@Override
	public void onSensorChanged(SensorEvent sensorEvent) {
		float ambientLightLux = sensorEvent.values[0];
		if(cameraPreview != null){
			if(ambientLightLux<=TOO_DARK_LUX){
				Log.d("AMBIENCE", "Too dark, trying to start flashlight");
				cameraPreview.setTorch(true);
			}
			else if(ambientLightLux >= BRIGHT_ENOUGH_LUX){
				Log.d("AMBIENCE", "Bright enough, turning off flashlight");
				cameraPreview.setTorch(false);
			}
		}
	}

}
