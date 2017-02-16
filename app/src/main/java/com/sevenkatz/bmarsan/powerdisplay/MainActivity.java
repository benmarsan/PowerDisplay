package com.sevenkatz.bmarsan.powerdisplay;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@TargetApi(21)
public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private BluetoothAdapter mBluetoothAdapter;
    private int REQUEST_ENABLE_BT = 1;
    private Handler mHandler;
    private static final long SCAN_PERIOD = 10000;
    private BluetoothLeScanner mLEScanner;
    private ScanSettings settings;
    private List<ScanFilter> filters;
    private BluetoothGatt mGatt;
    private BluetoothDevice mDevice;

    // State variables
    private boolean mIsScanning = false;
    private boolean mIsConnected = false;
    private boolean mIsConnecting = false;
    private boolean mIsDisconnecting = false;

    private boolean cpm_subscribed = false;
    private boolean pm_debug_force_subscribed = false;
    private boolean pm_debug_rotation_subscribed = false;

    // Values
    int power = 0;
    float force = 0;
    float rotation = 0;

    // UI
    private TextView statusText;
    private TextView powerText;
    private TextView forceText;
    private TextView rotationText;

    static final UUID CP_SERVICE = UUID
            .fromString("00001818-0000-1000-8000-00805f9b34fb");
    static final UUID BATTERY_SERVICE = UUID
            .fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID FIRMWARE_REVISON_UUID = UUID
            .fromString("00002a26-0000-1000-8000-00805f9b34fb");
    static final UUID DIS_UUID = UUID
            .fromString("0000180a-0000-1000-8000-00805f9b34fb");
    static final UUID CYCLING_POWER_MEASUREMENT_CHARAC = UUID
            .fromString("00002A63-0000-1000-8000-00805f9b34fb");
    static final UUID BATTERY_LEVEL_CHARAC = UUID
            .fromString("00002A19-0000-1000-8000-00805f9b34fb");
    static final UUID CCC = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID PM_DEBUG_SERVICE = UUID
            .fromString("a7c9d7b7-327f-4949-a368-8ac5e0aece4e");
    static final UUID PM_DEBUG_FORCE_CHAR = UUID
            .fromString("a7c9d7b7-327f-4949-a368-8ac5e0aece4f");
    static final UUID PM_DEBUG_ROTATION_CHAR = UUID
            .fromString("a7c9d7b7-327f-4949-a368-8ac5e0aece50");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Find the toolbar view inside the activity layout
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        // Sets the Toolbar to act as the ActionBar for this Activity window.
        // Make sure the toolbar exists in the activity and is not null
        setSupportActionBar(toolbar);

        statusText = (TextView) findViewById(R.id.statusText);
        powerText = (TextView) findViewById(R.id.powerText);
        forceText = (TextView) findViewById(R.id.forceText);
        rotationText = (TextView) findViewById(R.id.rotationText);

        mHandler = new Handler();

        // Check for BLE compatibility
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE Not Supported",
                    Toast.LENGTH_SHORT).show();
            finish();
        }

        if (Build.VERSION.SDK_INT >= 23) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can detect beacons.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @RequiresApi(api = Build.VERSION_CODES.M)
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
            final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        updateStatusText();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            if (Build.VERSION.SDK_INT >= 21) {
                mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
                settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                filters = new ArrayList<ScanFilter>();
                ScanFilter cp_service_filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(CP_SERVICE)).build();
                filters.add(cp_service_filter);
            }
            scanLeDevice(true);
            updateStatusText();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            scanLeDevice(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (mGatt == null) {
            super.onDestroy();
            return;
        }
        mGatt.close();
        mGatt = null;
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_CANCELED) {
                //Bluetooth not enabled.
                finish();
                return;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mIsScanning = false;
                    updateStatusText();

                    if (Build.VERSION.SDK_INT < 21) {
                        mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    } else {
                        mLEScanner.stopScan(mScanCallback);
                    }
                }
            }, SCAN_PERIOD);

            mIsScanning = true;

            if (Build.VERSION.SDK_INT < 21) {
                mBluetoothAdapter.startLeScan(mLeScanCallback);
            } else {
                mLEScanner.startScan(filters, settings, mScanCallback);
            }
        } else {
            mIsScanning = false;

            if (Build.VERSION.SDK_INT < 21) {
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            } else {
                mLEScanner.stopScan(mScanCallback);
            }
        }

        updateStatusText();
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.i("callbackType", String.valueOf(callbackType));
            Log.i("result", result.toString());
            BluetoothDevice btDevice = result.getDevice();
            connectToDevice(btDevice);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult sr : results) {
                Log.i("ScanResult - Results", sr.toString());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            mIsScanning = false;

            Log.e("Scan Failed", "Error Code: " + errorCode);
        }
    };

    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi,
                                     byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Log.i("onLeScan", device.toString());
                            connectToDevice(device);
                        }
                    });
                }
            };

    public void connectToDevice(BluetoothDevice device) {
        if (mGatt == null) {
            mGatt = device.connectGatt(this, false, gattCallback);
            scanLeDevice(false);// will stop after first device detection
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i("onConnectionStateChange", "Status: " + status);
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    mIsConnected = true;
                    Log.i("gattCallback", "STATE_CONNECTED");
                    gatt.discoverServices();
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    mIsConnected = false;
                    Log.e("gattCallback", "STATE_DISCONNECTED");
                    break;
                default:
                    Log.e("gattCallback", "STATE_OTHER");
            }

        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            List<BluetoothGattService> services = gatt.getServices();
            Log.i("onServicesDiscovered", services.toString());
            gatt.readCharacteristic(services.get(1).getCharacteristics().get(0));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if(!cpm_subscribed) {
                subscribeToNotification(mGatt, CP_SERVICE, CYCLING_POWER_MEASUREMENT_CHARAC);
                cpm_subscribed = true;
            }

            /*
            for(BluetoothGattService service: services) {
                Log.i("onServicesDiscovered", "" + service.getUuid().equals(PM_DEBUG_SERVICE));

                if( false && service.getUuid().equals(CP_SERVICE)) {
                    BluetoothGattCharacteristic cpmChar = service.getCharacteristic(CYCLING_POWER_MEASUREMENT_CHARAC);
                    BluetoothGattDescriptor descriptor = cpmChar.getDescriptor(CCC);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mGatt.writeDescriptor(descriptor);
                    gatt.setCharacteristicNotification(cpmChar, true);

                } else if(service.getUuid().equals(PM_DEBUG_SERVICE)) {
                    BluetoothGattCharacteristic forceChar = service.getCharacteristic(PM_DEBUG_FORCE_CHAR);
                    BluetoothGattDescriptor forceCharDescriptor = forceChar.getDescriptor(CCC);
                    forceCharDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mGatt.writeDescriptor(forceCharDescriptor);
                    gatt.setCharacteristicNotification(forceChar, true);

                    BluetoothGattCharacteristic rotationChar = service.getCharacteristic(PM_DEBUG_ROTATION_CHAR);
                    BluetoothGattDescriptor rotationCharDescriptor = rotationChar.getDescriptor(CCC);
                    rotationCharDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mGatt.writeDescriptor(rotationCharDescriptor);
                    gatt.setCharacteristicNotification(rotationChar, true);
                } else {
                    log(service.getUuid().toString());
                    log("isDebugService: " + service.getUuid().equals(PM_DEBUG_SERVICE));
                }
            }*/
        }

        private void subscribeToNotification(BluetoothGatt gatt, UUID service, UUID characteristic) {
            BluetoothGattCharacteristic mChar = gatt.getService(service).getCharacteristic(characteristic);
            BluetoothGattDescriptor descriptor = mChar.getDescriptor(CCC);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mGatt.writeDescriptor(descriptor);
            gatt.setCharacteristicNotification(mChar, true);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            Log.i("onCharacteristicRead", characteristic.toString());
            gatt.disconnect();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic arg0) {
            // Wait for previous char to work before starting next one
            if(CYCLING_POWER_MEASUREMENT_CHARAC.equals(arg0.getUuid()))
            {
                if(!pm_debug_force_subscribed) {
                    subscribeToNotification(mGatt, PM_DEBUG_SERVICE, PM_DEBUG_FORCE_CHAR);
                    pm_debug_force_subscribed = true;
                }
            }
            if(PM_DEBUG_FORCE_CHAR.equals(arg0.getUuid()))
            {
                if(!pm_debug_rotation_subscribed) {
                    subscribeToNotification(mGatt, PM_DEBUG_SERVICE, PM_DEBUG_ROTATION_CHAR);
                    pm_debug_rotation_subscribed = true;
                }
            }

            log("Data received: " + arg0.getUuid().toString());
            if(arg0.getUuid().equals(CYCLING_POWER_MEASUREMENT_CHARAC)) {
                if(arg0.getValue().length < 4) {
                    log("cpm length too short!");
                    return;
                }

                power = arg0.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT16, 2);

                log("power: " + power + ", raw data: ");

                notifyPowerChanged(power);
            } else if(arg0.getUuid().equals(PM_DEBUG_FORCE_CHAR)) {
                if(arg0.getValue().length < 2) {
                    log("pm_debug_force length too short!");
                    return;
                }

                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                buf.put(arg0.getValue());
                buf.order(ByteOrder.LITTLE_ENDIAN);
                force = buf.getFloat(0);

                log("force: " + force + " length: " + arg0.getValue().length);

                notifyForceChanged(force);
            } else if(arg0.getUuid().equals(PM_DEBUG_ROTATION_CHAR)) {
                log("Getting rotation char!!!");
                if(arg0.getValue().length < 2) {
                    log("pm_debug_rotation length too short!");
                    return;
                }

                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                buf.put(arg0.getValue());
                buf.order(ByteOrder.LITTLE_ENDIAN);
                rotation = buf.getFloat(0);

                log("rotation: " + rotation + "");

                notifyRotationChanged(rotation);
            }
        }
    };

    // Menu icons are inflated just as they were with actionbar
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_connect_ble) {
            scanLeDevice(true);

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateStatusText() {
        statusText.setText("Scanning: " + mIsScanning + ", Connected: " + mIsConnected);
    }

    private boolean checkBtGatt(BluetoothGatt gatt) {
        return checkBtGatt(gatt, false);
    }

    private boolean checkBtGattOnlyLogError(BluetoothGatt gatt) {
        return checkBtGatt(gatt, true);
    }

    private synchronized boolean checkBtGatt(BluetoothGatt gatt, boolean onlyLogError) {
        if (mGatt == null) {
            if (!onlyLogError)
                log("checkBtGatt, btGatt == null => true");
            mGatt = gatt;
            return true;
        }
        if (mGatt == gatt) {
            if (!onlyLogError)
                log("checkBtGatt, btGatt == gatt => true");
            return true;
        }
        log("checkBtGatt, btGatt(" + mGatt + ") != gatt(" + gatt + ") => false");
        return false;
    }

    private void log(final String msg) {
        Log.d("btle", msg);
    }

    private void notifyPowerChanged(int power) {
        this.power = power;

        final int newPower = power;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                powerText.setText("" + newPower + " W");
            }
        });
    }

    private void notifyForceChanged(float force) {
        this.force = force;

        final float newForce = force;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                forceText.setText(newForce + " N");
            }
        });

    }

    private void notifyRotationChanged(float rotation) {
        this.rotation = rotation;

        final float newRotation = rotation;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rotationText.setText(newRotation + " rad/s");
            }
        });

    }

}
