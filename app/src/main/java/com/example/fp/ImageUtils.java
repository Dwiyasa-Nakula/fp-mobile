package com.example.fp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.Image;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;

public class ImageUtils {
    private Bitmap imageToBitmap(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getImage() == null) {
            Log.e("ImageUtils", "ImageProxy or its Image is null");
            return null;
        }

        try {
            Image image = imageProxy.getImage(); // Extract the Image from ImageProxy
            InputImage inputImage = InputImage.fromMediaImage(
                    image,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            int width = inputImage.getWidth();
            int height = inputImage.getHeight();

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawBitmap(bitmap, 0, 0, null);
            return bitmap;
        } catch (Exception e) {
            Log.e("ImageUtils", "Error converting ImageProxy to Bitmap", e);
            return null;
        }
    }
}
