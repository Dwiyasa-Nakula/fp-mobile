package com.example.fp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
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
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.io.output.ByteArrayOutputStream;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AIDetection extends AppCompatActivity {
    private static final String MODEL_FILE = "model.tflite"; // Replace with your model file name
    private Interpreter tflite;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener sensorEventListener;
    private TextView accelerometerStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aidetection);
        accelerometerStatus = findViewById(R.id.accelerometer_status);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize FaceDetector
        initializeFaceDetector();

        // Initialize Accelerometer
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        setupAccelerometerListener();


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            initializeCamera();
        }

        try {
            tflite = new Interpreter(loadModelFile());
        } catch (IOException e) {
            Log.e("TensorFlowLite", "Error loading model", e);
        }
    }

    private void initializeFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build();

        faceDetector = FaceDetection.getClient(options);
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
                        .setImageQueueDepth(2)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    Bitmap bitmap = convertImageProxyToBitmap(image);
                    if (bitmap != null) {
                        detectFatigue(bitmap);
                    }
                    image.close();
                });

                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("CameraX", "Error initializing camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private long lastInferenceTime = 0;
    private static final long INFERENCE_INTERVAL_MS = 500; // Adjust as needed

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImage(ImageProxy imageProxy) {
        if (System.currentTimeMillis() - lastInferenceTime < INFERENCE_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        lastInferenceTime = System.currentTimeMillis();
        try {
            if (imageProxy.getImage() != null) {
                InputImage inputImage = InputImage.fromMediaImage(
                        imageProxy.getImage(),
                        imageProxy.getImageInfo().getRotationDegrees()
                );

                faceDetector.process(inputImage)
                        .addOnSuccessListener(faces -> handleFaces(faces, imageProxy))
                        .addOnFailureListener(e -> Log.e("FaceDetection", "Failed to process image", e))
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                Log.e("ImageAnalysis", "ImageProxy.getImage() returned null");
                imageProxy.close();
            }
        } catch (Exception e) {
            Log.e("ImageAnalysis", "Error processing image", e);
        } finally {
            imageProxy.close(); // Ensure release even if there's an exception
        }
    }

    private void handleFaces(List<Face> faces, ImageProxy imageProxy) {
        for (Face face : faces) {
            // Here you can crop the bitmap or use the face bounding box
            Log.d("FaceDetection", "Face detected. Processing fatigue...");

            // Convert ImageProxy to Bitmap for fatigue detection
            Bitmap faceBitmap = convertImageProxyToBitmap(imageProxy);
            if (faceBitmap != null) {
                detectFatigue(faceBitmap);
            }
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap convertImageProxyToBitmap(ImageProxy imageProxy) {
        // Get the planes from the ImageProxy
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

        // Get the Y, U, and V buffers from the planes
        ByteBuffer yBuffer = planes[0].getBuffer();  // Y plane
        ByteBuffer uBuffer = planes[1].getBuffer();  // U plane
        ByteBuffer vBuffer = planes[2].getBuffer();  // V plane

        // Create a byte array that holds NV21 data
        byte[] nv21 = new byte[yBuffer.remaining() + uBuffer.remaining() + vBuffer.remaining()];

        // Copy the Y, U, and V planes into the nv21 array
        yBuffer.get(nv21, 0, yBuffer.remaining());
        vBuffer.get(nv21, yBuffer.remaining(), vBuffer.remaining());
        uBuffer.get(nv21, yBuffer.remaining() + vBuffer.remaining(), uBuffer.remaining());

        // Convert the NV21 byte array to a Bitmap
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 100, out);
        byte[] jpegData = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);

        // Close the ImageProxy
        imageProxy.close();

        return bitmap;
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        FileInputStream inputStream = new FileInputStream(getAssets().openFd(MODEL_FILE).getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = getAssets().openFd(MODEL_FILE).getStartOffset();
        long declaredLength = getAssets().openFd(MODEL_FILE).getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private Bitmap resizeBitmap(Bitmap originalBitmap) {
        return Bitmap.createScaledBitmap(originalBitmap, 145, 145, true); // Resizing to 145x145 as required by the model
    }

    private void detectFatigue(Bitmap bitmap) {
        // Resize the bitmap to the expected input size for the model
        Bitmap resizedBitmap = resizeBitmap(bitmap);

        // Convert the resized bitmap to TensorImage
        TensorImage tensorImage = new TensorImage(DataType.UINT8);
        tensorImage.load(resizedBitmap);

        // Access the image buffer and allocate normalized buffer
        ByteBuffer byteBuffer = tensorImage.getBuffer();
        ByteBuffer normalizedBuffer = ByteBuffer.allocateDirect(145 * 145 * 3 * 4); // Adjust to input size
        normalizedBuffer.order(ByteOrder.nativeOrder());

        // Normalize pixel values
        byteBuffer.rewind();
        while (byteBuffer.hasRemaining()) {
            float pixelValue = (byteBuffer.get() & 0xFF) / 255f;
            normalizedBuffer.putFloat(pixelValue);
        }
        normalizedBuffer.rewind();

        // Load normalized data into TensorBuffer
        TensorBuffer tensorBuffer = TensorBuffer.createFixedSize(new int[]{1, 145, 145, 3}, DataType.FLOAT32);
        tensorBuffer.loadBuffer(normalizedBuffer);

        // Create a buffer for the output (e.g., shape [1, 2])
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(new int[]{1, 4}, DataType.FLOAT32);

        // Run inference
        tflite.run(tensorBuffer.getBuffer(), outputBuffer.getBuffer().rewind());

        // Process the output
        float[] output = outputBuffer.getFloatArray();
        Log.d("FatigueDetection", "Output: " + output[0] + ", " + output[1]);

        TextView statusTextView = findViewById(R.id.status_text);
        if (output[1] > output[0]) {
            Log.d("FatigueDetection", "Fatigue detected!");
            runOnUiThread(() -> statusTextView.setText("Status: Fatigue Detected!"));
        } else {
            Log.d("FatigueDetection", "No fatigue detected.");
            runOnUiThread(() -> statusTextView.setText("Status: No Fatigue Detected."));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (faceDetector != null) {
            faceDetector.close();
        }
        if (tflite != null) {
            tflite.close();
        }
        if (sensorManager != null && sensorEventListener != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeCamera();
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
        }
    }
}
