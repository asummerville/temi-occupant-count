package com.linklab.occupantcount;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.hivemq.client.mqtt.MqttGlobalPublishFilter.ALL;
import static java.nio.charset.StandardCharsets.UTF_8;

public class MainActivity extends AppCompatActivity implements OnRobotReadyListener {
    private Robot robot;
    private Spinner occupantSpinner, cameraSpinner;
    private Button goButton, exitButton;
    private ImageView imageView;
    private EditText angleInput;
    static final String TAG = "OccupantCount";

    String selectedLocation = "";
    String initialLocationName = "starting position";
    boolean receivingImages = false;

    private static final String subscriptionTopic = "temi-data";

    // init type method -- creates tablet UI for testing purposes/ manual execution
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // instantiate robot
        robot = Robot.getInstance();

        // UI elements
        occupantSpinner = findViewById(R.id.spinner);
        cameraSpinner = findViewById(R.id.spinner_camera);
        goButton = findViewById(R.id.go_button);
        exitButton = findViewById(R.id.exit_button);
        imageView = findViewById(R.id.imageView);
        angleInput = findViewById(R.id.editTextAngle);

        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finishAndRemoveTask();
            }
        });

        // set options for selecting camera
        List<String> cameraOptions = new ArrayList<>();
        cameraOptions.add("Regular Lens");
        cameraOptions.add("Wide Angle Lens");

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, cameraOptions);

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(adapter);
        cameraSpinner.setSelection(1); // default to wide angle

        // check if temi has permission to use camera, read, write, etc
        checkPermissionsOrRequest();

        // listen for broadcast messages sent out by Camera2Service when captured images are ready
        IntentFilter filter = new IntentFilter();
        filter.addAction("imageReady");

        BroadcastReceiver updateUIReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context,
                                  Intent intent) {
                // use this flag to ensure we don't receive repeated broadcasts
                if(receivingImages) {
                    receivingImages = false;
                    Log.d(TAG, "onReceive: received broadcast from Camera2Service. Updating UI");
                    File imageFile = new File(Environment.getExternalStorageDirectory() + "/Pictures/image.jpg");

                    // update imageView to get a preview of the clicked picture for debugging
                    updateImageView(imageFile);
                    Log.d(TAG, "onReceive: updated imageView");

                    Log.d(TAG, "onReceive: about to send to slack");
                    sendImage(imageFile);

                    // move temi to its initial location
                    // only run this if it's the last room...

                    robot.goTo(initialLocationName);
                }
            }
        };

        registerReceiver(updateUIReceiver, filter);

        // ***this is where the lambda listener method was -- have to create some method that automates/schedules the process
        // scheduledCall(); // uncomment when scheduled system is desired

    }


    private void updateImageView(File imageFile) {
        Bitmap myBitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
        imageView.setImageBitmap(myBitmap);
    }

    // send/save image -- might not need
    private void sendImage(File file) {
        try {
            // save img to filesystem (specific folder -- currently just save as Pictures/image.jpg)?
        } catch (Exception ex) {
            // Handle the error
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        robot.addOnRobotReadyListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        robot.removeOnRobotReadyListener(this);
    }

    // main method for tablet UI testing: gets iaq locations, runs 'moveAndClickPicture' method with UI inputs
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onRobotReady(boolean isReady) {
        // get the list of locations from the robot and populate the spinner
        List<String> locations = robot.getLocations();
        List<String> conferenceLocs = new ArrayList<>();
        for (String location : locations) {
            if (location.startsWith("iaq")) { // (create the locations on the Temi)
                conferenceLocs.add(location);
            }
        }
        Collections.sort(conferenceLocs);
        Log.v(TAG, "locations = " + conferenceLocs);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, conferenceLocs);

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        occupantSpinner.setAdapter(adapter);

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "user pressed go button");

                selectedLocation = occupantSpinner.getSelectedItem().toString();
                int angle = Integer.parseInt(angleInput.getText().toString());
                int cameraId = cameraSpinner.getSelectedItemPosition();
                moveAndClickPicture(selectedLocation, angle, cameraId);

            }
        });
    }

    // main method to complete the task of moving to a location and taking a photo
    // cameraId: regular lens = 0, wide angle = 1
    private void moveAndClickPicture(String location, int headAngle, int cameraId) {
        // ensures that the broadcast message sent from the camera service is received
        receivingImages = true;

        // store current location so that we can go back to where we were
        // ^* when commanding the robot to go to multiple locations, we don't want it to go back to
        // its original position after every picture it takes...
        robot.saveLocation(initialLocationName);
        Log.d(TAG, "moveAndClickPicture: temporarily saved the current location");

        // setup a location change listener to obtain events when temi is moving
        OnGoToLocationStatusChangedListener listener = new OnGoToLocationStatusChangedListener() {
            @Override
            public void onGoToLocationStatusChanged(@NonNull String currentLoc,
                                                    @NonNull String status,
                                                    int descriptionId,
                                                    @NonNull String description) {
                Log.d(TAG, String.format("onGoToLocationStatusChanged: location: %s, status: %s, desc: %s", currentLoc, status, description));

                // if we've reached, adjust head angle and click picture
                if (currentLoc.equals(location) && status.equals("complete")) {
                    Log.d(TAG, "reached destination. now adjusting head angle.");
                    robot.tiltAngle(headAngle);
                    Log.d(TAG, "now clicking picture.");
                    clickPicture(cameraId); // the camera service sends a broadcast once done

                    // below else if: we won't want robot going back to initial position (until all pictures are taken)
                } else if(currentLoc.equals(initialLocationName) && status.equals("complete")) {
                    // when we've reached back to original position, remove the temporarily saved initial location
                    Log.d(TAG, "reached original position");
                    robot.deleteLocation(initialLocationName);
                    Log.d(TAG, "deleted temporary location");
                    // remove the listening to location changes
                    robot.removeOnGoToLocationStatusChangedListener(this);
                }
            }
        };

        robot.addOnGoToLocationStatusChangedListener(listener);
        // ask temi to go to the requested location
        robot.goTo(location);
    }

    private void checkPermissionsOrRequest() {
        // The request code used in ActivityCompat.requestPermissions()
        // and returned in the Activity's onRequestPermissionsResult()
        int PERMISSION_ALL = 1;
        String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.ACCESS_NETWORK_STATE
        };

        if (!hasPermissions(this, permissions)) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_ALL);
        }
    }

    public boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "hasPermissions: no permission for " + permission);
                    return false;
                } else {
                    Log.d(TAG, "hasPermissions: YES permission for " + permission);
                }
            }
        }
        return true;
    }

    // take picture using Camera2Service class
    private void clickPicture(int cameraId) {
        Intent cameraServiceIntent = new Intent(MainActivity.this, Camera2Service.class);

        // camera apis expect the cameraId to be a string
        // from testing, regular lens = 0, wide angle = 1
        String idString = Integer.toString(cameraId);
        cameraServiceIntent.putExtra("cameraId", idString);
        startService(cameraServiceIntent);
    }

    // schedules the process at a certain interval (30 min-1 hr?)
    private void scheduledCall() {

        // the task
        TimerTask scheduledTask = new Task();
        // create timer
        Timer timer = new Timer();
        // schedule
        timer.schedule(scheduledTask, 100, 1800000); // 180k milliseconds = 30 min

    }

    // class with the desired task to be completed by the scheduledCall method
    class Task extends TimerTask {
        @Override
        public void run() {
            // get the list of iaq locations from the robot
            List<String> locations = robot.getLocations();
            List<String> conferenceLocs = new ArrayList<>();
            for (String location : locations) {
                if (location.startsWith("iaq")) { // (create the locations on the Temi)
                    conferenceLocs.add(location);
                }
            }
            Collections.sort(conferenceLocs);
            Log.v(TAG, "locations = " + conferenceLocs);

            // run for all iaq locations
            for (String location : conferenceLocs) {
                moveAndClickPicture(location, 18, 1); // change angle
            }
        }
    }


}