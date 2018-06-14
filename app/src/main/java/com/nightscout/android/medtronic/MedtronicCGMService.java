package com.nightscout.android.medtronic;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.nightscout.android.dexcom.USB.HexDump;
import com.nightscout.android.upload.GlucometerRecord;
import com.nightscout.android.upload.MedtronicSensorRecord;
import com.nightscout.android.upload.Record;
import com.nightscout.android.upload.UploadHelper;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * This class is the service responsible of manage correctly the interface with the enlite.
 * @author lmmarguenda
 *
 */
public class MedtronicCGMService extends Service implements
		OnSharedPreferenceChangeListener {

	private static final String TAG = MedtronicCGMService.class.getSimpleName();

	private boolean listenerAttached = false;
	private UploadHelper uploader;
	private UsbSerialDevice mSerial;
	private Handler mHandlerCheckSerial = new Handler();// This handler runs readAndUpload Runnable which checks the USB device and NET connection. 
	private Handler mHandlerProcessRead = new Handler();// this Handler is used to process the messages parsed.
	private Handler mHandlerReviewParameters = new Handler();
	private boolean mHandlerActive = false;
	private SharedPreferences settings = null;// Here I store the settings needed to store the status of the service.
	private Runnable checker = null;
	private WifiManager wifiManager;
	private MedtronicReader medtronicReader = null;//Medtronic Reader 
	private BufferedMessagesProcessor processBufferedMessages = new BufferedMessagesProcessor();// Runnable which manages the message processing;
	private ArrayList<Messenger> mClients = new ArrayList<Messenger>(); // clients subscribed;
	private final Messenger mMessenger = new Messenger(new IncomingHandler()); // Target we publish for clients to send messages to IncomingHandler.
	private SharedPreferences prefs = null;// common application preferences
	private int calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;//calibration Selected
	private Handler mHandlerReloadLost = new Handler();// this Handler is used to upload records which upload failed due to a network error.
	private boolean connectedSent = false;
	private boolean isDestroying = false;
	private Object reloadLostLock = new Object();
	private Object checkSerialLock = new Object();
	private Object isUploadingLock = new Object();
	private Object buffMessagesLock = new Object();

	private boolean faking = false;

	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();

	}

	/**
	 * Handler of incoming messages from clients.
	 *
	 * @author lmmarguenda
	 */
	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MedtronicConstants.MSG_MEDTRONIC_FAKE:
					doFakeReadAndUpload();
					break;

				case MedtronicConstants.MSG_REGISTER_CLIENT:
					mClients.add(msg.replyTo);
					break;
				case MedtronicConstants.MSG_UNREGISTER_CLIENT:
					mClients.remove(msg.replyTo);
					break;

				case MedtronicConstants.MSG_MEDTRONIC_SEND_MANUAL_CALIB_VALUE:
					String value = msg.getData().getString("sgv");
					if (value == null || value.equals("")) {
						value = prefs.getString("manual_sgv", "");
					}
					Log.d(TAG, "Manual Calibration Received SGV " + value);
					try {
						if (medtronicReader != null && !value.equals("")) {
						    medtronicReader.processManualCalibrationDataMessage(Float.parseFloat(value), false, true);
						    sendMessageCalibrationDoneToUI();
						}
						else {
                            sendErrorMessageToUI("Calibration value empty");
                        }
					} catch (Exception e) {
						sendExceptionToUI("Error parsing Calibration", e);
					}
					break;
				case MedtronicConstants.MSG_MEDTRONIC_SEND_INSTANT_CALIB_VALUE:  // manually entered calibration.
					value = msg.getData().getString("sgv");
					if (value == null || value.equals("")) {
						value = prefs.getString("instant_sgv", "");
					}
					Log.d(TAG, "Instant Calibration received SGV " + value);
					try {
						if (medtronicReader != null && !value.equals("")) {
							if (prefs.getBoolean("mmolxl", false)) {
								medtronicReader.calculateInstantCalibration(Float.parseFloat(value) * 18f);
								sendMessageCalibrationDoneToUI();
							} else {
							    medtronicReader.calculateInstantCalibration(Float.parseFloat(value));
								sendMessageCalibrationDoneToUI();
							}
						} else {
							sendErrorMessageToUI("Calibration value empty");
						}
					} catch (Exception e) {
						sendExceptionToUI("Error parsing Calibration", e);
					}
					break;

				case MedtronicConstants.MSG_MEDTRONIC_CGM_REQUEST_PERMISSION:
					openUsbSerial();
					break;
				default:
					super.handleMessage(msg);
			}
		}
	}


	void sendExceptionToUI(String message, Exception e) {
		Log.e(TAG, message);
		sendErrorMessageToUI(message);
		StringBuffer sb1 = new StringBuffer("");
		sb1.append("EXCEPTION: " + e.getMessage() + " " + e.getCause());
		for (StackTraceElement st : e.getStackTrace())
		{
			sb1.append(st.toString());
		}
		sendErrorMessageToUI(sb1.toString());
		Log.e(TAG, sb1.toString());
	}
	/**
     * Sends a message to be printed in the display (DEBUG) or launches a pop-up message.
     * @param valuetosend

     */
	private void sendMessageToUI(String valuetosend) {
		Log.i("medtronicCGMService", "Sent message to UI" + valuetosend);
		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss - ");
		//get current date time with Date()
		Date date = new Date();
		valuetosend = dateFormat.format(date) + valuetosend;

		Log.d(TAG,"send Message To UI -> "+valuetosend);
		if (mClients != null && mClients.size() > 0) {
			for (int i = mClients.size() - 1; i >= 0; i--) {
				try {
					Message mSend = Message
							.obtain(null,
									MedtronicConstants.MSG_MEDTRONIC_CGM_MESSAGE_RECEIVED);
					Bundle b = new Bundle();
					b.putString("data", valuetosend);
					mSend.setData(b);
					mClients.get(i).send(mSend);

				} catch (RemoteException e) {
					// The client is dead. Remove it from the list; we are going
					// through the list from back to front so this is safe to do
					// inside the loop.
					mClients.remove(i);
				}
			}
		} else {
			displayMessage(valuetosend);
		}
	}
	/**
     * Sends an error message to be printed in the display (DEBUG) if it is repeated, It is not printed again. If UI is not visible, It will launch a pop-up message.
     * @param valuetosend - the value to send
     *
     */
	private void sendErrorMessageToUI(String valuetosend) {
		Log.e("medtronicCGMService", "Sent error message to UI: " + valuetosend);
		if (mClients != null && mClients.size() > 0) {
			for (int i = mClients.size() - 1; i >= 0; i--) {
				try {
					Message mSend = Message
							.obtain(null,
									MedtronicConstants.MSG_MEDTRONIC_CGM_ERROR_RECEIVED);
					Bundle b = new Bundle();
					b.putString("data", valuetosend);
					mSend.setData(b);
					mClients.get(i).send(mSend);

				} catch (RemoteException e) {
					// The client is dead. Remove it from the list; we are going
					// through the list from back to front so this is safe to do
					// inside the loop.
					mClients.remove(i);
				}
			}
		} else {
			displayMessage(valuetosend);
		}
	}

	/**
     * Sends message to the UI to indicate that the device is connected.
     */
	private void sendMessageConnectedToUI() {
		Log.i("medtronicCGMService", "Connected");
		if (!connectedSent){
			Log.d(TAG, "Send Message Connected to UI");
			connectedSent = true;
		}
		for (int i = mClients.size() - 1; i >= 0; i--) {
			try {
				Message mSend = Message.obtain(null,
						MedtronicConstants.MSG_MEDTRONIC_CGM_USB_GRANTED);
				mClients.get(i).send(mSend);

			} catch (RemoteException e) {
				// The client is dead. Remove it from the list; we are going
				// through the list from back to front so this is safe to do
				// inside the loop.
				mClients.remove(i);
			}
		}
	}
	
	/**
     * Sends message to the UI to indicate that a calibration has been made.
     */
	private void sendMessageCalibrationDoneToUI() {
		Log.i("medtronicCGMService", "Calibration done");
		for (int i = mClients.size() - 1; i >= 0; i--) {
			try {
				Message mSend = Message.obtain(null,
						MedtronicConstants.MSG_MEDTRONIC_CALIBRATION_DONE);
				mClients.get(i).send(mSend);

			} catch (RemoteException e) {
				// The client is dead. Remove it from the list; we are going
				// through the list from back to front so this is safe to do
				// inside the loop.
				mClients.remove(i);
			}
		}
	}
	
	/**
     * Sends message to the UI to indicate that the device is disconnected.
     */
	private void sendMessageDisconnectedToUI() {
		Log.i("medtronicCGMService", "Disconnected");
		if (connectedSent)
			Log.d(TAG, "Send Message Disconnected to UI");
		connectedSent = false;
		for (int i = mClients.size() - 1; i >= 0; i--) {
			try {
				Message mSend = Message.obtain(null,
						MedtronicConstants.MSG_MEDTRONIC_CGM_NO_PERMISSION);
				mClients.get(i).send(mSend);

			} catch (RemoteException e) {
				// The client is dead. Remove it from the list; we are going
				// through the list from back to front so this is safe to do
				// inside the loop.
				mClients.remove(i);
			}
		}
	}

	@Override
	public void onCreate() {
		//Debug.startMethodTracing();
		Log.d(TAG,"medCGM onCreate!");
		super.onCreate();
		StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
		StrictMode.setThreadPolicy(policy);

		settings = getSharedPreferences(MedtronicConstants.PREFS_NAME, 0);
		prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		prefs.edit().remove("isCheckedWUP").commit();
		prefs.registerOnSharedPreferenceChangeListener(this);
		


        if (prefs.contains("monitor_type")){
        	String type = prefs.getString("monitor_type", "1");
        	if ("2".equalsIgnoreCase(type)){
        		if (prefs.contains("calibrationType")){
        			type = prefs.getString("calibrationType", "3");
        			if ("3".equalsIgnoreCase(type))
        				calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;
        			else if ("2".equalsIgnoreCase(type)){
        				calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
        			}else
        				calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;
        		}
        	}
        }
      
		wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);



		medtronicReader = new MedtronicReader(getBaseContext(), mClients);
		
		Record auxRecord =  MedtronicCGMService.this.loadClassFile(new File(getBaseContext().getFilesDir(), "save.bin"));

     	SharedPreferences prefs = PreferenceManager
 				.getDefaultSharedPreferences(getBaseContext());
     
     	DecimalFormat df = new DecimalFormat("#.##");

     	if (auxRecord instanceof MedtronicSensorRecord){
            	
			MedtronicSensorRecord record = (MedtronicSensorRecord) auxRecord;
     		
     		if (prefs.getBoolean("mmolxl", false)){
     			Float fBgValue;
				try{
					fBgValue =  (float)Integer.parseInt(record.bGValue);
					Log.i(TAG, "mmolxl true --> "+record.bGValue);
					record.bGValue = df.format(fBgValue/18f);
					Log.i(TAG, "mmolxl/18 true --> "+record.bGValue);
				}catch (Exception e){

				}
     		}else
     			Log.i(TAG,"mmolxl false --> "+record.bGValue);


    	    	if (prefs.getBoolean("isWarmingUp",false)){
    	    		record.bGValue = "W_Up";
    	    		record.trendArrow="---";
    	    	}
    	    	medtronicReader.previousRecord = record;           	
            }

        checker = medtronicReader.new CalibrationStatusChecker(mHandlerReviewParameters);
		mHandlerReviewParameters.postDelayed(checker, MedtronicConstants.TIME_5_MIN_IN_MS);
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		registerReceiver(mUsbReceiver, filter);
		mHandlerCheckSerial.removeCallbacks(readAndUpload);
		mHandlerCheckSerial.post(readAndUpload);
		mHandlerReloadLost.postDelayed(reloadLostRecords, 60000);
		mHandlerActive = true;

	}

	@Override
	public void onDestroy() {
		//Debug.stopMethodTracing();
		Log.d(TAG,"medCGM onDestroy!");
		isDestroying = true;
		prefs.unregisterOnSharedPreferenceChangeListener(this);

		synchronized (reloadLostLock) {
			mHandlerReloadLost.removeCallbacks(reloadLostRecords);
		}
		synchronized (checkSerialLock) {
			Log.i(TAG, "onDestroy called");

			mHandlerCheckSerial.removeCallbacks(readAndUpload);

			SharedPreferences.Editor editor = settings.edit();
			editor.putLong("lastDestroy", System.currentTimeMillis());
			editor.commit();
			closeUsbSerial();
			mHandlerActive = false;
			unregisterReceiver(mUsbReceiver);
		}

		synchronized (isUploadingLock) {
			super.onDestroy();
		}
	}
	

	

	/**
	 * Runnable.
	 * It checks that it is a serial device available, and there is Internet connection.
	 * It also binds readByListener with the serial device and execute it the first time;
	 */
	private Runnable readAndUpload = new Runnable() {
		public void run() {
			Log.d(TAG, "run readAndUpload");
			try {
				UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
				boolean hasPermission = faking;
				for (final UsbDevice usbDevice : usbManager.getDeviceList()
						.values()) {
					if (usbManager.hasPermission(usbDevice)) {
						hasPermission = true;
					}
				}
				if (!hasPermission) {
					synchronized (checkSerialLock) {
						Log.d(TAG, "I have lost usb permission changing listener attached to false...");
						listenerAttached = false;

						sendMessageDisconnectedToUI();
						if (!mHandlerActive || isDestroying){
							Log.d(TAG,"destroy readAnd Upload "+ mHandlerActive + " isDes "+ isDestroying);
							return;
						}
						mHandlerCheckSerial.removeCallbacks(readAndUpload);
						mHandlerCheckSerial.postDelayed(readAndUpload, MedtronicConstants.FIVE_SECONDS__MS);
						return;
					}
				} else
					sendMessageConnectedToUI();
				boolean connected;

				connected = isConnected();

				if (connected) {
					if (!isOnline())
						sendErrorMessageToUI("Internet connection error");
					if (!listenerAttached) {
						Log.d(TAG, "!listener attached readByListener triggered");


						listenerAttached = true;
						

					}

				} else {
					openUsbSerial();
					connected = isConnected();

					if (!connected)
						sendErrorMessageToUI("Receptor connection error");
					else if (!isOnline())
						sendErrorMessageToUI("Not online/connection error");
					else {
						sendMessageConnectedToUI();
						sendMessageToUI("connected");
					}
				}

			} catch (Exception e) {
				sendExceptionToUI("Unable to read from receptor or upload", e);
			}

				if (!mHandlerActive || isDestroying){
					Log.d(TAG,"destroy readAnd Upload2 "+ mHandlerActive + " isDes "+ isDestroying);
					return;
				}
				mHandlerCheckSerial.removeCallbacks(readAndUpload);
				mHandlerCheckSerial.postDelayed(readAndUpload,  MedtronicConstants.FIVE_SECONDS__MS);

		}
	};
	


	public void doFakeReadAndUpload() {
		try {
			faking = true;
			sendMessageConnectedToUI();


			ArrayList<byte[]> bufferedMessages = new ArrayList<>();
			List<String> devices = medtronicReader.getKnownDevices();
			String device = devices.get(0);

			//byte deviceData[] = {(byte) 0x21, (byte) 0x22, (byte) 0x23};
			byte deviceData[] = HexDump.hexStringToByteArray(device);

			bufferedMessages.add(TestUSBData.fakeSensorData(deviceData,5500));


			Log.d(TAG, "Fake Stream Received");
			if (bufferedMessages.size() > 0) {
				Log.d(TAG, "Fake Stream Received--> There are " + bufferedMessages.size() + " to process ");
				synchronized (buffMessagesLock) {
					processBufferedMessages.bufferedMessages
							.addAll(bufferedMessages);
				}
				if (!isDestroying) {
					Log.d(TAG, "Stream Received--> order process bufferedMessages ");
					mHandlerProcessRead.post(processBufferedMessages);
				}
			} else {
				Log.d(TAG, "NULL doReadAndUpload");
			}

		} catch (Exception e) {

			sendExceptionToUI("FakeUpload Exception", e);
		}
	}

	/**
	 * Process all the parsed messages, checks if there is Records to upload and executes the uploader if necessary.
	 */
	protected void doReadAndUpload(byte[] bytes) {
		try {
			Log.d(TAG, "doReadAndUpload");
			ArrayList<byte[]> bufferedMessages = medtronicReader.readFromReceiver(bytes);
			if (bufferedMessages != null && bufferedMessages.size() > 0) {
				Log.d(TAG, "doReadAndUpload: there are " + bufferedMessages.size() + " to process ");

				processBufferedMessages.bufferedMessages.addAll(bufferedMessages);
				mHandlerProcessRead.post(processBufferedMessages);
			}
			else{
				Log.d(TAG, "Nothing to do in doReadAndUpload");
			}
		} catch (Exception e) {
			sendExceptionToUI("doReadAndUpload exception", e);
		}
	}

	
	/**
	 * class This class process all the messages received after being correctly
	 * parsed.
	 */
    private Runnable reloadLostRecords = new Runnable() {
        public void run() {

            try {
                JSONArray recordsNotUploadedJson = new JSONArray(settings.getString("recordsNotUploadedJson", "[]"));
                synchronized (reloadLostLock) {
                    if (isOnline()) {
                        if (recordsNotUploadedJson.length() > 0 && !isDestroying) {
                            Log.i(TAG, "Uploading" + recordsNotUploadedJson.length() + " lost records");
                            uploader = new UploadHelper(getApplicationContext());
                        }
                    }
                }
            } catch (JSONException e) {
                sendExceptionToUI("Error Reloading Lost Records", e);
            }

            if (!isDestroying)
                mHandlerReloadLost.postDelayed(reloadLostRecords, 60000);
        }
    };
	/**
	 * class This class process all the messages received after being correctly
	 * parsed.
	 */
	private class BufferedMessagesProcessor implements Runnable {
		public ArrayList<byte[]> bufferedMessages = new ArrayList<byte[]>();
		public void run() {
			Log.d(TAG,"Processing bufferedMessages ");
			synchronized (isUploadingLock) {
				
				try {
					ArrayList<byte[]> bufferedMessages2Process;

					synchronized (buffMessagesLock) {
						bufferedMessages2Process = new ArrayList<byte[]>(bufferedMessages);
						bufferedMessages.clear();
					}
					Log.d(TAG,"I am going to process "+ bufferedMessages2Process.size()+" Messages");

					medtronicReader.processBufferedMessages(bufferedMessages2Process);

					// execute uploader
					List<Record> listToUpload = new ArrayList<Record>();
					// upload sensor values if available
					if (medtronicReader.lastElementsAdded > 0
							&& medtronicReader.lastRecordsInMemory != null
							&& medtronicReader.lastRecordsInMemory.size() >= medtronicReader.lastElementsAdded) {
						listToUpload
								.addAll(medtronicReader.lastRecordsInMemory
										.getListFromTail(medtronicReader.lastElementsAdded));// most
																								// recent
																								// First
						medtronicReader.lastElementsAdded = 0;
					}
					// upload glucometer value if available
					if (medtronicReader.lastGlucometerRecord != null) {
						listToUpload.add(medtronicReader.lastGlucometerRecord);
						medtronicReader.lastGlucometerRecord = null;
					}
					// upload device info if available
					if (medtronicReader.lastMedtronicPumpRecord != null) {
						listToUpload.add(medtronicReader.lastMedtronicPumpRecord);
						medtronicReader.lastMedtronicPumpRecord = null;
					}
	
	
					Record[] params = new Record[listToUpload.size()];
					for (int i = listToUpload.size() - 1; i >= 0; i--) {
						Record record = listToUpload.get(i);
						params[listToUpload.size() - 1 - i] = record;
					}
					if (params.length > 0) {
						synchronized (reloadLostLock) {
							uploader = new UploadHelper(getApplicationContext());
							
							uploader.execute(params);
						}
					}
	
					listToUpload.clear();
					if (prefs.getBoolean("EnableWifiHack", false)) {
						doWifiHack();
					}
				} catch (Exception e) {
					sendExceptionToUI("BufferedMessagesProcessor", e);
				}
			}
			Log.d(TAG,"Buffered Messages Processed ");
		}

	}

	private void doWifiHack() {
		Handler handler = new Handler();
		handler.postDelayed(new Runnable() {
			@Override
			// Interesting case: location with lousy wifi
			// toggle it off to use cellular
			// toggle back on for next try
			public void run() {
				Status dataUp = uploader.getStatus();
				if (dataUp == Status.RUNNING) {
					uploader.cancel(true);

					if (wifiManager.isWifiEnabled()) {
						wifiManager.setWifiEnabled(false);
						try {
							Thread.sleep(2500);
						} catch (InterruptedException e) {
							Log.e(TAG,
									"Sleep after setWifiEnabled(false) interrupted",
									e);
						}
						wifiManager.setWifiEnabled(true);
						try {
							Thread.sleep(2500);
						} catch (InterruptedException e) {
							Log.e(TAG,
									"Sleep after setWifiEnabled(true) interrupted",
									e);
						}
					}
				}

			}
		}, 22500);
	}

	private boolean isConnected() {
		return mSerial != null || faking;
	}

	private boolean isOnline() {
		ConnectivityManager connectivity = (ConnectivityManager) getBaseContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            NetworkInfo[] info = connectivity.getAllNetworkInfo();
            if (info != null) {
                for (int i = 0; (i < info.length); i++) {

                    if (info[i].getState() == NetworkInfo.State.CONNECTED) {
                        return true; 
                    }
                }
				Log.d(TAG, "INTERNET nothing connected of "+String.valueOf(info.length));
            }
        }
        return false;
	}

	/**
     * Launches a pop up message
     * @param message
     */
	private void displayMessage(String message) {
		Toast toast = Toast.makeText(getBaseContext(), message,
				Toast.LENGTH_LONG);
		toast.setGravity(Gravity.CENTER, 0, 0);
		LinearLayout toastLayout = (LinearLayout) toast.getView();
		TextView toastTV = (TextView) toastLayout.getChildAt(0);
		if (toastTV != null) {
			toastTV.setTextSize(20);
			toastTV.setGravity(Gravity.CENTER_VERTICAL
					| Gravity.CENTER_HORIZONTAL);
		}
		toast.show();

	}

	private void openUsbSerial() {
		if (faking) return;

		if (mSerial == null) {
			UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
			UsbDevice device;
			UsbDeviceConnection connection;

			HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
			if (!usbDevices.isEmpty()) {
				boolean keep = true;
				device = null;
				connection = null;
				for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
					device = entry.getValue();
					int deviceVID = device.getVendorId();
					int devicePID = device.getProductId();
					if (deviceVID != 0x1d6b || (devicePID != 0x0001 || devicePID != 0x0002 || devicePID != 0x0003)) {
						// Excludes USB hubs
						Log.d(TAG, "Device: vendor " + deviceVID + " - ID " + devicePID);
						connection = usbManager.openDevice(device);
						keep = false;
					} else {
						connection = null;
						device = null;
					}

					if (!keep)
						break;//stop search on first usb device
				}

				if (device != null && connection != null) mSerial = UsbSerialDevice.createUsbSerialDevice(device, connection);
			}
			if (mSerial == null) {
				Toast.makeText(this, "cannot open / null device", Toast.LENGTH_SHORT).show();
				Log.e(TAG, "mSerial==null");
				return;
			}

			if (mSerial.open()) {

				mSerial.setBaudRate(57600);
				mSerial.setDataBits(8);
				mSerial.setParity(0);
				mSerial.setStopBits(1);

				mSerial.read(mCallback);


				Toast.makeText(this, "connected", Toast.LENGTH_SHORT)
						.show();
			}
			else {
				Toast.makeText(this, "couldn't open device", Toast.LENGTH_SHORT)
						.show();
				Log.e(TAG, "could not open device)");
			}
		}

	}



	 //Deserialize the EGVRecord (most recent) value
    public Record loadClassFile(File f) {
    	 ObjectInputStream ois = null;
        try {
            ois = new ObjectInputStream(new FileInputStream(f));
            Object o = ois.readObject();
            ois.close();
            return (Record) o;
        } catch (Exception ex) {
            Log.w(TAG, " unable to loadEGVRecord");
            try{
	            if (ois != null)
	            	ois.close();
            }catch(Exception e){
            	Log.e(TAG, " Error closing ObjectInputStream");
            }
        }
        return new Record();
    }
	
	private void closeUsbSerial() {


		mHandlerProcessRead.removeCallbacks(processBufferedMessages);

		mHandlerReviewParameters.removeCallbacks(checker);
		listenerAttached = false;

	}

	/**
	 * BroadcastReceiver when insert/remove the device USB plug into/from a USB port 
	 */
	BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				sendMessageDisconnectedToUI();
				closeUsbSerial();
			}
		}
	};
	
	/**
	 * Method inherited from "OnSharedPreferenceChangeListener"
	 * Here we listen to the change of some preferences of interest to keep or remove the status
	 * of our application.
	 */
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		try {


			if (sharedPreferences.contains("monitor_type") && key.equalsIgnoreCase("monitor_type")){
	        	String type = sharedPreferences.getString("monitor_type", "1");
	        	if ("2".equalsIgnoreCase(type)){
	        		if (sharedPreferences.contains("calibrationType")){
	        			type = sharedPreferences.getString("calibrationType", "3");
	        			if ("3".equalsIgnoreCase(type)){
	        				calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;
	        			}else if ("2".equalsIgnoreCase(type)){

	        				calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
	        				//start handler to ask for sensor calibration value

	        			}else{
	        				calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;

	        			}

						medtronicReader.calibrationSelected = calibrationSelected;

	        		}
	        	}
	        }
			
			if (sharedPreferences.contains("calibrationType") && key.equalsIgnoreCase("calibrationType")){

				String type = sharedPreferences.getString("calibrationType", "3");
    			if ("3".equalsIgnoreCase(type)){
    				calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;

    			}else if ("2".equalsIgnoreCase(type)){
    				calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
    				//start handler to ask for sensor calibration value

    			}else{
    				calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;

    			}
				medtronicReader.calibrationSelected = calibrationSelected;
    		}

			if (key.equals("medtronic_cgm_id") || key.equals("glucometer_cgm_id") || key.equals("sensor_cgm_id")) {
				String newID = sharedPreferences.getString("medtronic_cgm_id", "");
				if (!"".equals(newID.replaceAll(" ", ""))) {
					mHandlerCheckSerial.removeCallbacks(readAndUpload);
					byte[] newIdPump = HexDump.hexStringToByteArray(newID);
					if (key.equals("medtronic_cgm_id") && !Arrays.equals(newIdPump, medtronicReader.idPump)) {
						SharedPreferences.Editor editor = settings.edit();
						editor.remove("lastGlucometerMessage");
						editor.remove("previousValue");
						editor.remove("expectedSensorSortNumber");
						editor.remove("knownDevices");
						editor.remove("isCalibrating");
						editor.remove("previousValue");
						editor.remove("expectedSensorSortNumber");
						editor.remove("lastGlucometerValue");
						editor.remove("lastGlucometerDate");
						editor.remove("expectedSensorSortNumberForCalibration0");
						editor.remove("expectedSensorSortNumberForCalibration1");
						editor.commit();
						synchronized (checkSerialLock) {

							mHandlerCheckSerial.removeCallbacks(readAndUpload);

							mHandlerActive = false;

						}
						medtronicReader = new MedtronicReader(
								getBaseContext(), mClients);
						medtronicReader.idPump = newIdPump;
						synchronized (checkSerialLock) {
							mHandlerCheckSerial.post(readAndUpload);
							mHandlerActive = true;
						}
					}else{
						if (key.equalsIgnoreCase("glucometer_cgm_id") && prefs.contains("glucometer_cgm_id")) {
							if (prefs.getString("glucometer_cgm_id", "").length() > 0) {
								if (!medtronicReader.knownDevices.contains(prefs.getString("glucometer_cgm_id", ""))){
									medtronicReader.knownDevices.add(prefs.getString("glucometer_cgm_id", ""));
								}

							}
						}
						if (key.equalsIgnoreCase("sensor_cgm_id") && prefs.contains("sensor_cgm_id")) {
							if (prefs.getString("sensor_cgm_id", "").length() > 0) {
								String sensorID = HexDump.toHexString(Integer.parseInt(prefs
										.getString("sensor_cgm_id", "0")));
								while (sensorID != null && sensorID.length() > 6) {
									sensorID = sensorID.substring(1);
								}
								if (!medtronicReader.knownDevices.contains(sensorID)){
									medtronicReader.knownDevices.add(sensorID);
								}
							}
						}

						mHandlerCheckSerial.post(readAndUpload);
					}
				}
			}
			
		} catch (Exception e) {
			sendExceptionToUI("", e);
		}
	}


	private UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {

		@Override
		public void onReceivedData(byte[] arg0)
		{
			doReadAndUpload(arg0);
					// Code here :)
		}
	};

}
