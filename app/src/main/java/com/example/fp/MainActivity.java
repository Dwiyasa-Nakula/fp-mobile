package com.example.fp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

// MainActivity.java
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button navigateButton = findViewById(R.id.navigate_to_ai_detection);
        navigateButton.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, AIDetection.class);
            startActivity(intent);
        });
    }
}