package com.example.fp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
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
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AIDetection extends AppCompatActivity {
    private static final String MODEL_FILE = "tflite_models.tflite"; // Replace with your model file name
    private Interpreter tflite;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aidetection);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize FaceDetector
        initializeFaceDetector();

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
            imageProxy.close();
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
        Image.Plane[] planes = (Image.Plane[]) imageProxy.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        byte[] nv21 = new byte[yBuffer.remaining() + uBuffer.remaining() + vBuffer.remaining()];
        yBuffer.get(nv21, 0, yBuffer.remaining());
        vBuffer.get(nv21, yBuffer.remaining(), vBuffer.remaining());
        uBuffer.get(nv21, yBuffer.remaining() + vBuffer.remaining(), uBuffer.remaining());

        // Convert NV21 to Bitmap
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 100, out);
        byte[] jpegData = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        FileInputStream inputStream = new FileInputStream(getAssets().openFd(MODEL_FILE).getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = getAssets().openFd(MODEL_FILE).getStartOffset();
        long declaredLength = getAssets().openFd(MODEL_FILE).getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void detectFatigue(Bitmap bitmap) {
        // Resize bitmap to the model's expected input size (e.g., 224x224)
        Bitmap resizedBitmap = resizeBitmap(bitmap, 224, 224); // Example size, adjust based on your model

        // Convert to TensorImage
        TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
        tensorImage.load(resizedBitmap);

        // Prepare output buffer
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(new int[]{1, 2}, DataType.FLOAT32);

        // Run inference
        tflite.run(tensorImage.getBuffer(), outputBuffer.getBuffer().rewind());

        float[] output = outputBuffer.getFloatArray();
        Log.d("FatigueDetection", "Output: " + output[0] + ", " + output[1]);

        if (output[1] > output[0]) {
            Log.d("FatigueDetection", "Fatigue detected!");
        } else {
            Log.d("FatigueDetection", "No fatigue detected.");
        }
    }

    private Bitmap resizeBitmap(Bitmap bitmap, int width, int height) {
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
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