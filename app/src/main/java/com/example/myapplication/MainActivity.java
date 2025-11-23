package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;


import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResult;

import android.provider.MediaStore;
import android.se.omapi.Session;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import android.view.Menu;
import android.view.MenuItem;

import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.Executors;

import static android.Manifest.permission.CAMERA;

import com.example.myapplication.TFLiteInference;

public class MainActivity extends AppCompatActivity {

    private Uri photoURI;
    private String currentPhotoPath;
    private CameraCaptureSession ourCameraCaptureSession;
    private String stringCameraID;
    private CameraManager cameraManager;
    private CameraDevice ourCameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;

    private TextView resultTextView;
    private Button inferenceButton;

    //Code for Camera Manager, Device, CaptureSession and Builder was derived from: https://www.youtube.com/watch?v=bEhqGpI0kew

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        resultTextView = findViewById(R.id.resultTextView);
        inferenceButton = findViewById(R.id.recognizeButton);
        inferenceButton.setOnClickListener(v -> recognizeBM());
        Button button = findViewById(R.id.button1);
        button.setOnClickListener(v -> openCamera());

        ActivityCompat.requestPermissions(this, new String[]{CAMERA}, PackageManager.PERMISSION_GRANTED);

        textureView = findViewById(R.id.textureView);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        startCamera();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            ourCameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            ourCameraDevice.close();
            ourCameraDevice = null;
        }

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            ourCameraDevice = camera;
        }
    };

    private void startCamera() {

        //ID: 1 is for front facing camera
        try {
            stringCameraID = cameraManager.getCameraIdList()[1];

            if (ActivityCompat.checkSelfPermission(this, CAMERA) != PackageManager.PERMISSION_GRANTED){
                return;
            }

            cameraManager.openCamera(stringCameraID, stateCallback, null);
        }
        catch (CameraAccessException e){
            throw new RuntimeException(e);
        }

    }

    private TextureView textureView;

    public void buttonStartVideoFeed(View view){

        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        Surface surface = new Surface(surfaceTexture);

        try {
            captureRequestBuilder = ourCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            captureRequestBuilder.addTarget(surface);

            OutputConfiguration outputConfiguration = new OutputConfiguration(surface);

            SessionConfiguration sessionConfiguration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, Collections.singletonList(outputConfiguration),
                    getMainExecutor(),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            ourCameraCaptureSession.close();
                        }

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            ourCameraCaptureSession = session;
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                            try {
                                ourCameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                throw new RuntimeException(e);
                            }

                        }
                    }
            );


            ourCameraDevice.createCaptureSession(sessionConfiguration);

        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void buttonStopVideoFeed(View view){
        try {
            ourCameraCaptureSession.abortCaptures();
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void openCamera(){
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
//        startActivity(cameraIntent);
//        cameraLauncher.launch(cameraIntent);
        File photoFile = null;
        try{
            photoFile = createImageFile();
        } catch (IOException e) {
            Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show();
            return;
        }

        if (photoFile != null) {
            photoURI = FileProvider.getUriForFile(
                    this,
                    getApplicationContext().getPackageName() + ".provider",
                    photoFile
            );
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
            cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION); // crucial
            cameraLauncher.launch(cameraIntent);
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
        );
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "Photo saved at:\n" + currentPhotoPath, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Camera cancelled", Toast.LENGTH_SHORT).show();
                        }
                    }
            );

    private void recognizeBM() {
        if (textureView.isAvailable()) {
            Bitmap currentFrame = textureView.getBitmap();
            if (currentFrame != null) {
                runRecognitionInBackground(currentFrame);
            } else {
                Toast.makeText(this, "Could not capture frame from preview.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Camera preview is not available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void runRecognitionInBackground(Bitmap inputBitmap) {
        // Clear previous result display
        resultTextView.setText("Recognizing...");

        Executors.newSingleThreadExecutor().execute(() -> {
            String recognizedWord;
            Log.d(TFLiteInference.TAG, "Ping");

            if (inputBitmap != null) {
                try {
                    recognizedWord = TFLiteInference.recognizeWord(getApplicationContext(), inputBitmap);
                    Log.d(TFLiteInference.TAG, "Recognized word: " + recognizedWord);
                } catch (Exception e) {
                    Log.e(TFLiteInference.TAG, "TFLite Inference Error.", e);
                    recognizedWord = "INFERENCE ERROR: " + e.getMessage();
                }
            } else {
                recognizedWord = "ERROR: Input bitmap was null.";
            }

            String finalResult = recognizedWord;
            runOnUiThread(() -> {
                resultTextView.setText("Recognized Word: " + finalResult);
            });
        });
    }
}
