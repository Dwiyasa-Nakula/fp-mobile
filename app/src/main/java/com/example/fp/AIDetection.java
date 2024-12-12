package com.example.fp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AIDetection extends AppCompatActivity {

    private FaceDetector faceDetector;
    private ExecutorService cameraExecutor;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener sensorEventListener;
    private TextView accelerometerStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aidetection);
        accelerometerStatus = findViewById(R.id.accelerometer_status);

        // Initialize Executor first
        cameraExecutor = Executors.newSingleThreadExecutor();

        Log.d("CameraX", "Starting camera...");
        Log.d("FaceDetection", "Initializing face detector...");
        Log.d("ImageAnalysis", "Analyzing image...");
        Log.d("FatigueDetection", "Processing face...");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            initializeCamera();
        }

        // Initialize Face Detection
        initializeFaceDetection();

        // Initialize Accelerometer
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        setupAccelerometerListener();
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void initializeCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview use case
                Preview preview = new Preview.Builder().build();
                PreviewView previewView = findViewById(R.id.preview_view);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Select back camera
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Image Analysis use case
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    try {
                        if (image.getImage() != null) {
                            InputImage inputImage = InputImage.fromMediaImage(
                                    image.getImage(),
                                    image.getImageInfo().getRotationDegrees()
                            );

                            faceDetector.process(inputImage)
                                    .addOnSuccessListener(this::processFaces)
                                    .addOnFailureListener(e -> Log.e("FaceDetection", "Failed", e))
                                    .addOnCompleteListener(task -> image.close());
                        } else {
                            Log.e("ImageAnalysis", "Image is null");
                            image.close();
                        }
                    } catch (Exception e) {
                        Log.e("ImageAnalysis", "Error analyzing image", e);
                        image.close();
                    }
                });

                // Bind to lifecycle
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("CameraX", "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void initializeFaceDetection() {
        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .build();

        faceDetector = FaceDetection.getClient(options);
    }

    private void processFaces(@NonNull List<Face> faces) {
        for (Face face : faces) {
            if (face.getHeadEulerAngleY() > 30 || face.getHeadEulerAngleY() < -30) {
                Log.d("FatigueDetection", "Head turned: Possible fatigue");
            }
            if (face.getSmilingProbability() != null && face.getSmilingProbability() < 0.3) {
                Log.d("FatigueDetection", "Not smiling: Possible fatigue");
            }
        }
    }

    private void setupAccelerometerListener() {
        if (accelerometer == null) {
            Log.e("Accelerometer", "No accelerometer found on this device.");
            return;
        }

        sensorEventListener = new SensorEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];

                    double magnitude = Math.sqrt(x * x + y * y + z * z);

                    if (magnitude > 12) {
                        Log.d("Accelerometer", "Sudden movement detected: Magnitude = " + magnitude);
                        runOnUiThread(() -> accelerometerStatus.setText("Accelerometer Status: Sudden Movement Detected!"));
                    } else {
                        runOnUiThread(() -> accelerometerStatus.setText("Accelerometer Status: Normal"));
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // No action needed
            }
        };

        sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (sensorManager != null && sensorEventListener != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

}
