package com.example.myapplication;

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


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import android.provider.MediaStore;
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
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.Manifest.permission.CAMERA;
import static java.lang.Thread.sleep;

public class MainActivity extends AppCompatActivity {

    private Uri photoURI;
    private String currentPhotoPath;
    private CameraCaptureSession ourCameraCaptureSession;
    private String stringCameraID;
    private CameraManager cameraManager;
    private CameraDevice ourCameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;

    private OCRModel model;

    private TextView resultTextView;

    private volatile boolean isRecognizing;

    //Code for Camera Manager, Device, CaptureSession and Builder was derived from: https://www.youtube.com/watch?v=bEhqGpI0kew

    ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        resultTextView = findViewById(R.id.resultTextView);
        Button button = findViewById(R.id.button1);
        button.setOnClickListener(v -> openCamera());

        model = new OCRModel(getApplicationContext());

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

        //ID: 1 is for front facing camera medium
        //ID: 0 or 2 is for front facing camera Jonah's Phone
        try {
            stringCameraID = cameraManager.getCameraIdList()[0];

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

            recognizeBM();

        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void buttonStopVideoFeed(View view){

        if(isRecognizing){
            isRecognizing = false;
            executorService.shutdownNow();
        }

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
        if(!isRecognizing) {
            isRecognizing = true;

            executorService.execute(() -> {

                while(true){
                    Bitmap frame = textureView.getBitmap();

                    if (frame == null) {
                        resultTextView.setText("Error");
                        return;
                    }

                    String pred = model.runInference(frame);

                    runOnUiThread(() -> {
                        resultTextView.setText(pred);
                    });

                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

            });

        }
    }

}
