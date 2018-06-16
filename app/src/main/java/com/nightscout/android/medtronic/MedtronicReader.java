package com.nightscout.android.medtronic;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;

import android.text.TextUtils;
import android.util.Log;

import com.nightscout.android.dexcom.USB.HexDump;
import com.nightscout.android.upload.GlucometerRecord;
import com.nightscout.android.upload.MedtronicSensorRecord;
import com.nightscout.android.upload.Record;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;

/**
 * Class: MedtronicReader This class manages all read operations over all the
 * medtronic devices which are registered in Medtronic's pump. This class also
 * holds the shared variables to know when the application is commanding or it
 * has finished a request.
 * 
 * @author lmmarguenda
 * 
 */
public class MedtronicReader {
	private static final String TAG = MedtronicReader.class.getSimpleName();

	private Context context;

	protected byte[] idPump = null;
	protected byte[] notFinishedRead = null;


	public int crcErrorBytesToDiscard = 0;
	public boolean isCalibrating = false;
	public int calibrationStatus = MedtronicConstants.WITHOUT_ANY_CALIBRATION;
	public float calibrationIsigValue = -1f;
	public float calibrationFactor = -1f;
	public long lastCalibrationDate = 0;
	public long lastGlucometerDate = 0;
	public long lastSensorValueDate = 0;
	public float lastGlucometerValue = -1f;
	public byte[] expectedSensorSortNumberForCalibration = { (byte) 0xff,
			(byte) 0xff }; // expected indexes of the next sensor reading for
	// correct calibration
	public GlucometerRecord lastGlucometerRecord = null;// last glucometer
	// record read
	public byte expectedSensorSortNumber = (byte) 0xff; // expected index of the
	// next sensor reading
	public Boolean expectedSensorSortNumberLock = false; // expectedSensorSortNumber
	// Lock for
	// synchronize
	public MedtronicSensorRecord previousRecord = null; // last sensor record
	public Byte lastCommandSend = null; // last command sent from this

	// the receptor is sending a command
	// and we have no received the ACK

	// the receptor has sent a message
	// but we do not have the answer
	// yet.
	public CircleList<Record> lastRecordsInMemory = new CircleList<Record>(10);// array
	// to
	// store
	// last
	// Records
	public ArrayList<String> knownDevices = null; // list of devices that we
	// are going to listen (pump
	// included).
	public int lastElementsAdded = 0; // last records read from sensor
	ArrayList<Messenger> mClients = new ArrayList<Messenger>();
	private byte[] lastGlucometerMessage = null; // last glucometer message
	// received
	SharedPreferences settings = null;
	SharedPreferences prefs = null;
	Integer calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;

	/**
	 * Constructor
	 * 
	 *
	 * @param context
	 */
	public MedtronicReader(Context context,
			ArrayList<Messenger> mClients) {
		this.settings = context.getSharedPreferences(
				MedtronicConstants.PREFS_NAME, 0);

		this.mClients = mClients;
		this.context = context;
		knownDevices = new ArrayList<String>();

		prefs = PreferenceManager.getDefaultSharedPreferences(context);

		if (prefs.contains("calibrationType")) {
			String type = prefs.getString("calibrationType", "3");
			if ("3".equalsIgnoreCase(type))
				calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;
			else if ("2".equalsIgnoreCase(type)) {
				calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
			} else
				calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;
		}


		if (prefs.contains("medtronic_cgm_id")) {
			if (prefs.getString("medtronic_cgm_id", "").length() > 0) {
				knownDevices.add(prefs.getString("medtronic_cgm_id", ""));
				idPump = HexDump.hexStringToByteArray(prefs.getString(
						"medtronic_cgm_id", ""));
			}
		}
		if (prefs.contains("glucometer_cgm_id")) {
			if (prefs.getString("glucometer_cgm_id", "").length() > 0) {
				knownDevices.add(prefs.getString("glucometer_cgm_id", ""));

			}
		}
		if (prefs.contains("sensor_cgm_id")) {
			if (prefs.getString("sensor_cgm_id", "").length() > 0) {
				try {
					String sensorID = HexDump.toHexString(Integer.parseInt(prefs
							.getString("sensor_cgm_id", "0")));
					while (sensorID.length() > 6) {
						sensorID = sensorID.substring(1);
					}
					Log.d(TAG, "SensorID inserted "
							+ prefs.getString("sensor_cgm_id", "0")
							+ " transformed to " + sensorID);
					knownDevices.add(sensorID);
				}
				catch(NumberFormatException nfe){
					sendErrorMessageToUI("Sensor ID is incorrect - needs to be a number.  Ignored.");
				}
			}
		}


		if (settings.contains("lastSensorValueDate"))
			lastSensorValueDate = settings.getLong("lastSensorValueDate", 0);
		if (settings.contains("calibrationStatus"))
			calibrationStatus = settings.getInt("calibrationStatus",
					MedtronicConstants.WITHOUT_ANY_CALIBRATION);
		if (settings.contains("isCalibrating"))
			isCalibrating = settings.getBoolean("isCalibrating", false);
		if (settings.contains("lastGlucometerMessage")
				&& settings.getString("lastGlucometerMessage", "").length() > 0)
			lastGlucometerMessage = HexDump.hexStringToByteArray(settings
					.getString("lastGlucometerMessage", ""));
		if (settings.contains("calibrationFactor"))
			calibrationFactor = settings.getFloat("calibrationFactor",
                    this.calibrationFactor);
		if (settings.contains("lastCalibrationDate"))
			lastCalibrationDate = settings.getLong("lastCalibrationDate", 0);
		if (settings.contains("expectedSensorSortNumber")
				&& settings.getString("expectedSensorSortNumber", "").length() > 0) {
			expectedSensorSortNumber = HexDump.hexStringToByteArray(settings
					.getString("expectedSensorSortNumber", ""))[0];

		}
		if (settings.contains("lastGlucometerValue")
				&& settings.getFloat("lastGlucometerValue", -1) > 0) {
			lastGlucometerValue = settings.getFloat("lastGlucometerValue", -1);
		}
		if (settings.contains("lastGlucometerDate")
				&& settings.getLong("lastGlucometerDate", -1) > 0)
			lastGlucometerDate = settings.getLong("lastGlucometerDate", -1);
		if ((settings.contains("expectedSensorSortNumberForCalibration0") && settings
				.getString("expectedSensorSortNumberForCalibration0", "")
				.length() > 0)
				&& settings.contains("expectedSensorSortNumberForCalibration1")
				&& settings.getString(
						"expectedSensorSortNumberForCalibration1", "").length() > 0) {
			expectedSensorSortNumberForCalibration[0] = HexDump
					.hexStringToByteArray(settings.getString(
							"expectedSensorSortNumberForCalibration0", ""))[0];
			expectedSensorSortNumberForCalibration[1] = HexDump
					.hexStringToByteArray(settings.getString(
							"expectedSensorSortNumberForCalibration1", ""))[0];
		} else {
			if (isCalibrating) {
				expectedSensorSortNumberForCalibration[0] = (byte) 0x00;
				expectedSensorSortNumberForCalibration[1] = (byte) 0x71;
			}
		}
		checkCalibrationOutOfTime();
	}

	public List<String> getKnownDevices()
	{
		return Collections.unmodifiableList(knownDevices);
	}
	/**
	 * This method checks if the message received has its source in one of the
	 * devices registered.
	 * 
	 * @param readData
	 * @return true, if I "know" the source of this message.
	 */
	private boolean isMessageFromMyDevices(byte[] readData) {
		int initByte = firstByteOfDeviceId(readData);
		if (initByte < 0 || readData.length < initByte){
			Log.e(TAG, "Error checking initByte and received length, I can't check If is from 'My devices'");
			return false;
		}
		for (String knownDevice : knownDevices) {
			int nBytes = knownDevice.length() / 2;
			if (knownDevice.length() % 2 > 0 && knownDevice.length() > 2) {
				nBytes++;
			}
			if (readData.length < (nBytes + initByte)){
				Log.e(TAG, "Error checking received length, I can't check If is from 'My devices'");
				return false;
			}
			String deviceCode = HexDump.toHexString(readData, initByte, nBytes);
			
			if (knownDevice.toLowerCase().equals(deviceCode.toLowerCase()))
				return true;
			else
				Log.e(TAG, "Current Known Device "+knownDevice+" Message Received From "+deviceCode);
		}
		Log.i(TAG, "Message received from unknown device: " + HexDump.dumpHexString(readData) + " I am expecting any of: " + TextUtils.join(", ", knownDevices));
		return false;
	}


	private void sendMessageToUI(String valuetosend) {

		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss - ");
		//get current date time with Date()
		Date date = new Date();
		valuetosend = dateFormat.format(date) + valuetosend;
		Log.i(TAG, valuetosend);
		// log.debug("MedtronicReader Sends to UI "+valuetosend);


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
		} /*
		 * else { displayMessage(valuetosend); }
		 */
	}
	/**
	 * 
	 * @param readData
	 * @return index of the first byte which contains the ID of the device.
	 */
	private int firstByteOfDeviceId(byte[] readData) {
		if (readData.length < 3)
			return -1;
		switch (readData[2]) {
		case MedtronicConstants.MEDTRONIC_PUMP:
		case MedtronicConstants.MEDTRONIC_GL:
			return 3;
		case MedtronicConstants.MEDTRONIC_SENSOR1:
		case MedtronicConstants.MEDTRONIC_SENSOR2:
			return 4;
		default:
			return -1;
		}
	}

	/**
	 * 
	 * @param readData
	 * @return index of the first byte after device ID
	 */
	private int firstByteAfterDeviceId(byte[] readData) {
		int initByte = firstByteOfDeviceId(readData);
		if (initByte < 0 || readData.length < initByte)
			return -1;
		return 3 + initByte; // IDs are always 3 bytes..
	}

	/**
	 * This function checks that the first byte of the received message is
	 * correct.
	 * 
	 * @param first
	 * @return true, if the first byte is one of the send/receive values
	 */
	private boolean checkFirstByte(byte first) {
		return (first == (byte) 0x02) || (first == (byte) 0x81)
				|| (first == (byte) 0x01) || (first == (byte) 0xC1)
				|| (first == (byte) 0x03) || (first == (byte) 0x13);
	}

	/**
	 * 
	 * @param first
	 * @return A constant which tell us the kind of answer received.
	 */
	private int getAnswerType(byte first) {
		if (first == (byte) 0x02)
			return MedtronicConstants.DATA_ANSWER;
		else if ((first == (byte) 0x81) || (first == (byte) 0x01)
				|| (first == (byte) 0xC1))
			return MedtronicConstants.COMMAND_ANSWER;
		else if ((first == (byte) 0x03) || (first == (byte) 0x13))
			return MedtronicConstants.FILTER_COMMAND;
		else if (first == (byte) 0x82) {
			sendErrorMessageToUI("Garbled/incomplete message received");
			return MedtronicConstants.CRC_ERROR;
		}
		else
			return MedtronicConstants.UNKNOWN_ANSWER;
	}

	/**
	 * This method checks if the calibration has got too old (over 12 hours)
	 */
	private void checkCalibrationOutOfTime() {
		if ((calibrationFactor > 0)
				&& (calibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION)
				&& calibrationStatus != MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS
				&& calibrationStatus != MedtronicConstants.CALIBRATION_MORE_THAN_12H_OLD) {
			if (lastCalibrationDate > 0
					&& (System.currentTimeMillis() - lastCalibrationDate) > MedtronicConstants.TIME_12_HOURS_IN_MS) {
				calibrationStatus = MedtronicConstants.CALIBRATION_MORE_THAN_12H_OLD;
				SharedPreferences.Editor editor = settings.edit();
				editor.putInt("calibrationStatus", calibrationStatus);
				editor.apply();
			}
		}

	}

	/**
	 * This method reads from the serial device, and process the answer
	 *
	 * */
	public ArrayList<byte[]> readFromReceiver(byte[] readFromDevice) {
		ArrayList<byte[]> bufferedMessages = null;
		Log.d(TAG, "READ " + readFromDevice.length + " bytes: " + HexDump.dumpHexString(readFromDevice));

		try {
			bufferedMessages = parseMessageData(
					Arrays.copyOfRange(readFromDevice, 0, readFromDevice.length), readFromDevice.length);
			checkCalibrationOutOfTime();

		} catch (Exception e) {
			sendErrorMessageToUI(e.toString());
			bufferedMessages = new ArrayList<byte[]>();
		}
		return bufferedMessages;
	}

	private void processDataAnswer(byte[] readData)
	{
		int calibrationSelectedAux;
		Log.d(TAG, "processDataAnswer");

		calibrationSelectedAux = calibrationSelected;

		if (isMessageFromMyDevices(readData)) {
			Log.d(TAG, "IS FROM MY DEVICES");
			switch (readData[2]) {
				case MedtronicConstants.MEDTRONIC_PUMP:
					Log.d(TAG, "Pump message received");
					processPumpDataMessage(readData);
					break;
				case MedtronicConstants.MEDTRONIC_GL: {
					Log.d(TAG, "GLUCOMETER DATA RECEIVED");
					if (lastGlucometerMessage == null
							|| lastGlucometerMessage.length == 0) {
						lastGlucometerMessage = Arrays
								.copyOfRange(readData, 0,
										readData.length);
						SharedPreferences.Editor editor = settings
								.edit();
						editor.putString(
								"lastGlucometerMessage",
								HexDump.toHexString(lastGlucometerMessage));
						editor.apply();
					} else {
						boolean isEqual = Arrays
								.equals(lastGlucometerMessage,
										readData);
						if (isEqual
								&& (System.currentTimeMillis()
								- lastGlucometerDate < MedtronicConstants.TIME_15_MIN_IN_MS)) {
							return;
						}
						lastGlucometerDate = System
								.currentTimeMillis();
						lastGlucometerMessage = Arrays
								.copyOfRange(readData, 0,
										readData.length);
					}

					processGlucometerDataMessage(readData);
					if (lastGlucometerValue > 0) {
						isCalibrating = calibrationSelectedAux == MedtronicConstants.CALIBRATION_GLUCOMETER;
						if (previousRecord == null) {
							MedtronicSensorRecord auxRecord = new MedtronicSensorRecord();

							auxRecord.isCalibrating = calibrationSelectedAux == MedtronicConstants.CALIBRATION_GLUCOMETER;
							writeLocalCSV(auxRecord, context);
							Log.d(TAG, "No previous record - 1");

						} else {
							previousRecord.isCalibrating = calibrationSelectedAux == MedtronicConstants.CALIBRATION_GLUCOMETER;
							writeLocalCSV(previousRecord, context);
							Log.d(TAG, "Has previous record - 2");

						}
						SharedPreferences.Editor editor = settings
								.edit();

						editor.putBoolean("isCalibrating", calibrationSelectedAux == MedtronicConstants.CALIBRATION_GLUCOMETER);
						if (calibrationSelectedAux == MedtronicConstants.CALIBRATION_GLUCOMETER)
							sendMessageToUI("isCalibrating");
						sendMessageToUI("glucometer data received");

						editor.apply();
					}

				}
				break;
				case MedtronicConstants.MEDTRONIC_SENSOR1:
				case MedtronicConstants.MEDTRONIC_SENSOR2:
					Log.d(TAG, "SENSOR DATA RECEIVED");
					if (prefs.getString("glucSrcTypes", "1")
							.equals("2")) {
						Log.d(TAG,"Sensor value received, but value is took only by pump logs");
						break;
					}
					processSensorDataMessage(readData);
					sendMessageToUI("sensor data value processed");
					break;
				default:
					Log.i(TAG, "No Match");
					break;
			}
		} else {
			Log.i(TAG,
					"I don't have to listen to this. This message comes from another source: " + HexDump.dumpHexString(readData));

		}


	}
					/**
                     * This method process all the parsed messages got using "readFromReceiver"
                     * function
                     *
                     * @param bufferedMessages
                     *            , List of parsed messages.
                     */
	public void processBufferedMessages(ArrayList<byte[]> bufferedMessages) {

		Log.d(TAG, "processBufferedMessages");

		try {
			for (byte[] readData : bufferedMessages) {
				if (checkFirstByte(readData[0])) {
					switch (getAnswerType(readData[0])) {
					case MedtronicConstants.DATA_ANSWER:
						Log.d(TAG, "DataAnswer recevied");
						processDataAnswer(readData);
						break;
					case MedtronicConstants.COMMAND_ANSWER:
						Log.d(TAG, "ACK Received");
						break;
					case MedtronicConstants.FILTER_COMMAND:
						if (readData[0] == (byte) 0x13)
							; // FILTER DEACTIVATED
						else
							; // FILTER ACTIVATED
						break;
					default: {
						Log.d(TAG, "I don't understand this message "
								+ HexDump.toHexString(readData));

					}
					}
				} else {
					Log.d(TAG, "Invalid message start packet:" + HexDump.dumpHexString(readData));
				}
			}
		} catch (Exception ex2) {
			sendErrorMessageToUI(ex2.toString());
		}

	}

	/**
	 * This function parses the raw bytes to correct messages or discards the
	 * wrong bytes.
	 * 
	 * @param readData
	 *            , array of bytes read
	 * @param read
	 *            , length of bytes read
	 * @return ArrayList of parsed messages.
	 */
	private ArrayList<byte[]> parseMessageData(byte[] readData, int read) {
		byte[] readBuffer = null;
		Log.d(TAG, "Parsing message");
		ArrayList<byte[]> messageList = new ArrayList<byte[]>();
		if (notFinishedRead == null || notFinishedRead.length <= 0) {
			readBuffer = Arrays.copyOf(readData, read);
		} else {
			readBuffer = Arrays.copyOf(notFinishedRead, notFinishedRead.length
					+ read);
			for (int i = 0; i < read; i++) {
				readBuffer[notFinishedRead.length + i] = readData[i];
			}
			notFinishedRead = null;
		}

		int i = 0;
		if (crcErrorBytesToDiscard > 0)
			i = crcErrorBytesToDiscard;
		crcErrorBytesToDiscard = 0;
		while (i < readBuffer.length) {
			int answer = getAnswerType(readBuffer[i]);
			if (answer == MedtronicConstants.COMMAND_ANSWER) {
				Log.d(TAG, "COMMAND");
				if (readBuffer.length >= i + 3)
					messageList.add(Arrays.copyOfRange(readBuffer, i, i + 3));
				else {
					notFinishedRead = Arrays.copyOfRange(readBuffer, i,
							readBuffer.length);
					return messageList;
				}
				i += 3;
			} else if (answer == MedtronicConstants.FILTER_COMMAND) {
				Log.d(TAG, "FILTERCOMMAND");
				messageList.add(Arrays.copyOfRange(readBuffer, i, i + 1));
				i++;
			} else if (answer == MedtronicConstants.CRC_ERROR) {
				Log.d(TAG, "CRC ERROR");

				if (readBuffer.length <= i + 1) {
					notFinishedRead = Arrays.copyOfRange(readBuffer, i,
							readBuffer.length);
					return messageList;
				}
				int length = HexDump.unsignedByte(readBuffer[i + 1]);
				if (length <= 0) {
					i++;
					continue;
				}

				if (readBuffer.length >= i + length + 2) {
					i = i + length + 2;
				} else {
					crcErrorBytesToDiscard = (i + length + 2)
							- readBuffer.length;
					return messageList;
				}
			} else if (answer == MedtronicConstants.DATA_ANSWER) {
				Log.d(TAG, "DATA_ANSWER");
				if (readBuffer.length <= i + 1) {
					notFinishedRead = Arrays.copyOfRange(readBuffer, i,
							readBuffer.length);
					return messageList;
				}
				int length = HexDump.unsignedByte(readBuffer[i + 1]);
				if (length <= 0) {
					i++;
					continue;
				}
				if (readBuffer.length >= i + length + 2) {
					messageList.add(Arrays.copyOfRange(readBuffer, i, i
							+ length + 2));
					i = i + length + 2;// I have to add 2 bytes CTRL byte and
					// SIZE byte
				} else {
					notFinishedRead = Arrays.copyOfRange(readBuffer, i,
							readBuffer.length);
					return messageList;
				}
			} else {
				i++;
			}
		}
		return messageList;
	}

	/**
	 * This method process the pump answers
	 * 
	 * @param readData
	 * @return String, for debug or notification purposes
	 */
	public void processPumpDataMessage(byte[] readData) {
		int commandByte = firstByteAfterDeviceId(readData);
		if (commandByte < 0)
			return;
		if (lastCommandSend == null)
			return;
		switch (readData[commandByte]) {
		case MedtronicConstants.MEDTRONIC_GET_LAST_PAGE:
			Log.d(TAG,"Pump get last page command received");
			return;
		case MedtronicConstants.MEDTRONIC_READ_PAGE_COMMAND:
			Log.d(TAG,"Pump read page command received");
			return;
		case MedtronicConstants.MEDTRONIC_GET_PUMP_MODEL:
			Log.d(TAG, "Pump Model Received");
			sendMessageToUI("Pump Model Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_GET_ALARM_MODE:
			Log.d(TAG,"Pump Alarm Mode Received");
			return;
		case MedtronicConstants.MEDTRONIC_GET_PUMP_STATE:
			Log.d(TAG,"Pump Status Received");
			sendMessageToUI("Pump Status Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_GET_TEMPORARY_BASAL:
			Log.d(TAG,"Pump Temporary Basal Received");
			return;
		case MedtronicConstants.MEDTRONIC_GET_BATTERY_STATUS:
			Log.d(TAG, "Pump Battery Status Received");
			sendMessageToUI("Pump Battery Status Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_GET_REMAINING_INSULIN:
			Log.i(TAG,"Pump Remaining Insulin Received");
			sendMessageToUI("Pump Remaining Insulin Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_GET_REMOTE_CONTROL_IDS:
			Log.i(TAG, "Pump Remote Control Ids Received");
			sendMessageToUI("Pump Remote Control Ids Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_GET_PARADIGM_LINK_IDS:
			Log.i(TAG, "Pump Paradigm Link Ids Received");
			sendMessageToUI("Pump Paradigm Link Ids Received... this is ignored");
			return ;
		case MedtronicConstants.MEDTRONIC_GET_SENSORID:
			Log.i(TAG,"Pump Sensor Id Received");
			sendMessageToUI("Pump Sensor Id Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_GET_CALIBRATION_FACTOR:
			Log.i(TAG,"Pump Calibration Factor Received");
			sendMessageToUI("Pump Cal. Factor Received... this is ignored");
			return;
		case MedtronicConstants.MEDTRONIC_ACK:
			Log.i(TAG,"Pump Ack Received");
			return;
		default:
			Log.e(TAG, "Undecoded Command");
		}
	}



	/**
	 * This method process the glucometer messages
	 * 
	 * @param readData
	 * @return String, for debug or notification purposes
	 */
	public void processGlucometerDataMessage(byte[] readData) {
		int firstMeasureByte = firstByteAfterDeviceId(readData);
		if (firstMeasureByte < 0) {
            Log.e(TAG, "Error, I can not identify the initial byte of the glucometer measure");
            return;
        }
		int numBytes = (int) readData[1];
        if (firstMeasureByte > readData.length || numBytes > readData.length) {
            Log.e(TAG, "Error, I have detected an error in glucometer message size");
            return;
        }

        int ub = readData[firstMeasureByte]  & 0xff;
        int lb = readData[firstMeasureByte + 1]  & 0xff;
        int num = lb + (ub << 8);
		sendMessageToUI(String.format("Glucometer reading seen: %d  / %.2f", num, ((float)num )/18.0));

        if (num < 0 || num > 1535) {
            Log.e(TAG, "Glucometer value under 0 or over 0x5ff. Possible ACK or malfunction.");
            return;
        }
		processManualCalibrationDataMessage(num, true, false);
	}

	

	/**
	 * This method process the Manual Calibration message
	 * 
	 */
	public void processManualCalibrationDataMessage(float value,
			boolean instant, boolean doCalibration) {
		float mult = 1f;
		//if (prefs.getBoolean("mmolxl", false))
			//mult = 1f/18f;
		float num = value * mult;
		lastGlucometerRecord = new GlucometerRecord();
		lastGlucometerRecord.numGlucometerValue = num;
		lastGlucometerValue = num;
		Date d = new Date();
		lastGlucometerRecord.setDate(d);
		lastGlucometerDate = d.getTime();

		if (!instant && doCalibration) {
			if (HexDump.unsignedByte(expectedSensorSortNumber) == HexDump
					.unsignedByte((byte) 0xff)) {
				expectedSensorSortNumberForCalibration[0] = (byte) 0x00;
				expectedSensorSortNumberForCalibration[1] = (byte) 0x71;
			} else {
				synchronized (expectedSensorSortNumberLock) {
					byte expectedAux = expectedSensorSortNumber;
					if ((expectedSensorSortNumber & (byte) 0x01) > 0)
						expectedAux = (byte) (expectedSensorSortNumber & (byte) 0xFE);
					expectedSensorSortNumberForCalibration[0] = calculateNextSensorSortNameFrom(
							6, expectedAux);
					expectedSensorSortNumberForCalibration[1] = calculateNextSensorSortNameFrom(
							10, expectedAux);
				}
			}
		}
		SharedPreferences.Editor editor = settings.edit();
		editor.putFloat("lastGlucometerValue", num);
		editor.putLong("glucometerLastDate", d.getTime());
		if (!instant && doCalibration) {
			editor.putString("expectedSensorSortNumberForCalibration0", HexDump
					.toHexString(expectedSensorSortNumberForCalibration[0]));
			editor.putString("expectedSensorSortNumberForCalibration1", HexDump
					.toHexString(expectedSensorSortNumberForCalibration[1]));
		} else {
			editor.remove("expectedSensorSortNumberForCalibration0");
			editor.remove("expectedSensorSortNumberForCalibration1");
		}
		if (lastGlucometerValue > 0) {
			isCalibrating = !instant && doCalibration;
			if (previousRecord == null) {
				MedtronicSensorRecord auxRecord = new MedtronicSensorRecord();
				auxRecord.isCalibrating = !instant;

			} else {
				previousRecord.isCalibrating = !instant;

			}
			editor.putBoolean("isCalibrating", !instant);
			editor.apply();
		}
		editor.apply();
		Log.i(TAG, "Manual calibration:" + num);
	}

	/**
	 * Apply calibration factor to a value in "index" position of the sensor
	 * message
	 * 
	 * @param previousCalibrationFactor
	 * @param previousCalibrationStatus
	 * @param isig
	 * @param record
	 * @param added
	 * @param currentTime
	 */
	private void calibratingBackwards(float previousCalibrationFactor,
			int previousCalibrationStatus, float isig,
			MedtronicSensorRecord record, int added, Date currentTime) {
		if (previousCalibrationFactor > 0) {
			record.setUnfilteredGlucose(isig * previousCalibrationFactor);
			record.setBGValue((applyFilterToRecord(record)) );
			record.isCalibrating = false;
			record.calibrationFactor = previousCalibrationFactor;
			if (previousCalibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION) {
				record.calibrationStatus = previousCalibrationStatus;
			} else {
				record.calibrationStatus = MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS;
			}
		}
		setRecordDateHistoric(record, currentTime, added);
	}

	/**
	 * Apply calibration to the "current" value of the sensor message
	 * 
	 * @param difference
	 * @param isig
	 * @param readData
	 * @param index
	 * @param record
	 * @param num
	 * @param currentTime
	 */
	private void calibratingCurrentElement(long difference, float isig,
			byte[] readData, int index, MedtronicSensorRecord record, int num,
			Date currentTime) {
		boolean calibrated = false;
		// currentMeasure = num;
		if (isCalibrating) {
			if (num > 0) {
				calculateCalibration(difference, isig, readData[index]);
				if (calibrationFactor > 0) {
					if (!isCalibrating) {
						if (calibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION
								&& calibrationStatus != MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS
								&& calibrationStatus != MedtronicConstants.CALIBRATION_MORE_THAN_12H_OLD) {
							record.setBGValue( lastGlucometerValue );
							record.setUnfilteredGlucose(lastGlucometerValue);
							record.calibrationFactor = calibrationFactor;
							record.isCalibrating = false;
							record.calibrationStatus = calibrationStatus;
							lastCalibrationDate = currentTime.getTime();
							SharedPreferences.Editor editor = settings.edit();

							editor.putLong("lastCalibrationDate",
									lastCalibrationDate);
							editor.apply();
							calibrated = true;
						}
					}
				}
			}
		}
		if (calibrationFactor > 0 && !calibrated) {

			if (calibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION) {
				record.setUnfilteredGlucose(isig * calibrationFactor);
				record.setBGValue(applyFilterToRecord(record));
				record.isCalibrating = false;
				record.calibrationFactor = calibrationFactor;
				record.calibrationStatus = calibrationStatus;
			} else {
				record.setUnfilteredGlucose(isig * calibrationFactor);
				record.setBGValue(applyFilterToRecord(record));
				record.isCalibrating = false;
				record.calibrationFactor = calibrationFactor;
				record.calibrationStatus = MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS;
			}
		}
		record.setDate(currentTime);
		previousRecord = record;
	}

	/*

	Apply calibration to all upcoming data, immediately.

	 */
	public void calculateInstantCalibration(float currentMeasure) {
		if (previousRecord != null && previousRecord.isig != 0) {
			calibrationFactor = currentMeasure / previousRecord.isig;
			Log.d(TAG,"Instant Calibration result " + calibrationFactor + "; ISIG is " + previousRecord.isig);
			if (calibrationFactor > 0) {
				previousRecord.setBGValue(currentMeasure);
				calibrationStatus = MedtronicConstants.CALIBRATED;
				lastCalibrationDate = System.currentTimeMillis();
				isCalibrating = false;
				previousRecord.isCalibrating = false;
				previousRecord.calibrationStatus = calibrationStatus;

 				writeLocalCSV(previousRecord, context);

				SharedPreferences.Editor editor = settings.edit();

				editor.putLong("lastCalibrationDate", lastCalibrationDate);
				editor.putBoolean("isCalibrating", isCalibrating);
				editor.putFloat("calibrationFactor", calibrationFactor);
				editor.putInt("calibrationStatus", calibrationStatus);
				editor.apply();
			}
		}
		else {
			sendErrorMessageToUI("I can't calibrate, I don't have any recent stored sensor reading yet. Try again after sensor transmits again.");
			Log.d(TAG,"Could not instant calibrate. I dont have ISIG from a previous record yet.");
		}
	}

	/**
	 * This method process the sensor answers
	 * 
	 * @param readData
	 * @return String, for debug or notification purposes
	 */
	public void processSensorDataMessage(byte[] readData) {
		Date d = new Date();
		long difference = 0;
		if (isCalibrating && lastGlucometerDate > 0) {
			difference = d.getTime() - lastGlucometerDate;
		}

		int added = 8;
		int firstMeasureByte = firstByteAfterDeviceId(readData);
		float isig = 0;

		if (firstMeasureByte < 0)
			return;
		int numBytes = HexDump.unsignedByte(readData[1]);/* length is of payload after first two bytes */
		if (firstMeasureByte > readData.length || numBytes > readData.length + 2
				|| numBytes <= 0)
			return;
		int previousCalibrationStatus = calibrationStatus;
		float previousCalibrationFactor = calibrationFactor;
		short adjustement = (short) readData[firstMeasureByte + 2];
		long firstTimeOut = d.getTime() - lastSensorValueDate;
		if (expectedSensorSortNumber == (byte) 0xff
				|| lastSensorValueDate == 0
				|| (firstTimeOut >= MedtronicConstants.TIME_10_MIN_IN_MS)) {
			Log.i("Medtronic", "First reading - or missed last 40 minutes - Backfilling old data");

			lastElementsAdded = 0;
			// I must read ALL THE MEASURES
			synchronized (expectedSensorSortNumberLock) {
				expectedSensorSortNumber = readData[firstMeasureByte + 3];
			}

			for (int i = 20; i >= 0; i -= 2) {
				if (i >= 4 && i < 8) {
					continue;
				}
				lastElementsAdded++;



				int ub = readData[firstMeasureByte + 4 + i] & 0xff;
				int lb = readData[firstMeasureByte + 5 + i] & 0xff;
				int num = lb + (ub << 8);

				MedtronicSensorRecord record = new MedtronicSensorRecord();
				record.isCalibrating = isCalibrating;
				isig = calculateISIG(num, adjustement);
				record.setIsig(isig);
				if (i == 0) {
					calibratingCurrentElement(difference, isig, readData,
							firstMeasureByte + 3, record, num, d);
				} else {
					calibratingBackwards(previousCalibrationFactor,
							previousCalibrationStatus, isig, record, added, d);
				}
				added--;
				lastRecordsInMemory.add(record);
				calculateTrendAndArrow(record, lastRecordsInMemory);

			}

		} else {

			if (expectedSensorSortNumber == readData[firstMeasureByte + 3]
					||calculateNextSensorSortNameFrom(1, expectedSensorSortNumber) == readData[firstMeasureByte + 3]) {
				Log.i("Medtronic", "Expected sensor number received!!");

				lastElementsAdded = 0;
				// I must read only the first value except if byte ends in "1"
				// then I skip this value
				if (!isSensorRepeatedMessage(readData[firstMeasureByte + 3])
						|| HexDump
						.unsignedByte((byte) (expectedSensorSortNumber & (byte) 0x01)) < 1
						&& HexDump
						.unsignedByte((byte) (readData[firstMeasureByte + 3] & (byte) 0x01)) == 1) {

					int ub = readData[firstMeasureByte + 4] & 0xff;
					int lb = readData[firstMeasureByte + 5] & 0xff;
					int num = lb + (ub << 8);

					Log.d(TAG, "Read from sensor: value is " + num);
					MedtronicSensorRecord record = new MedtronicSensorRecord();
					isig = calculateISIG(num, adjustement);
					record.setIsig(isig);
					record.isCalibrating = isCalibrating;
					calibratingCurrentElement(difference, isig, readData,
							firstMeasureByte + 3, record, num, d);
					lastRecordsInMemory.add(record);
					calculateTrendAndArrow(record, lastRecordsInMemory);

					lastElementsAdded++;
				} else {
					// sendMessageToUI("ES REPETIDO NO LO EVALUO ", false);
					synchronized (expectedSensorSortNumberLock) {
						expectedSensorSortNumber = calculateNextSensorSortNameFrom(
								1, expectedSensorSortNumber);
					}
					return;
				}
			} else {
				Log.i("Medtronic", "NOT Expected sensor number received!!");
				int dataLost = -1;
				if (previousRecord != null || lastSensorValueDate > 0) {
					long timeDiff = 0;
					if (previousRecord != null)
						timeDiff = d.getTime() - previousRecord.getDate().getTime();
					else
						timeDiff = d.getTime() - lastSensorValueDate;
					if (timeDiff > (MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_10_MIN_IN_MS)) {
						dataLost = 10;
						added = 8;
					} else {
						int valPrev = transformSequenceToIndex(expectedSensorSortNumber);
						int currentVal = transformSequenceToIndex(readData[firstMeasureByte + 3]);
						if (valPrev > currentVal)
							currentVal = 8 + currentVal;
						dataLost = (currentVal - (valPrev)) % 9;
						if (dataLost < 0)
							dataLost *= -1;
						dataLost--;
						added = dataLost;
						Log.i(TAG, " valPrev " + valPrev
								+ " currentVal " + currentVal + " dataLost "
								+ dataLost + " added " + added);
					}
				} else {
					dataLost = 10;
					added = 8;
				}
				Log.i(TAG, "Data Lost " + dataLost);
				if (dataLost >= 0) {
					if (dataLost >= 2)
						dataLost += 2;
					if (dataLost > 10) {
						dataLost = 10;
						added = 8;
					}
					dataLost *= 2;
					lastElementsAdded = 0;
					// I must read ALL THE MEASURES
					if (dataLost == 20 || dataLost == 0) {
						synchronized (expectedSensorSortNumberLock) {
							expectedSensorSortNumber = readData[firstMeasureByte + 3];
						}
					}

					for (int i = dataLost; i >= 0; i -= 2) {
						if (i >= 4 && i < 8) {
							continue;
						}
						lastElementsAdded++;

						int ub = readData[firstMeasureByte + 4 + i] & 0xff;
						int lb = readData[firstMeasureByte + 5 + i] & 0xff;
						int num = lb + (ub << 8);
						MedtronicSensorRecord record = new MedtronicSensorRecord();
						record.isCalibrating = isCalibrating;
						isig = calculateISIG(num, adjustement);
						record.setIsig(isig);
						if (i == 0) {
							calibratingCurrentElement(difference, isig,
									readData, firstMeasureByte + 3, record,
									num, d);
						} else {
							calibratingBackwards(previousCalibrationFactor,
									previousCalibrationStatus, isig, record,
									added, d);
						}
						added--;
						lastRecordsInMemory.add(record);
						calculateTrendAndArrow(record, lastRecordsInMemory);

					}
				} else {

					int ub = readData[firstMeasureByte + 4] & 0xff;
					int lb = readData[firstMeasureByte + 5] & 0xff;
					int num = lb + (ub << 8);

					MedtronicSensorRecord record = new MedtronicSensorRecord();
					isig = calculateISIG(num, adjustement);
					record.setIsig(isig);
					record.isCalibrating = isCalibrating;
					calibratingCurrentElement(difference, isig, readData,
							firstMeasureByte + 3, record, num, d);
					lastRecordsInMemory.add(record);
					calculateTrendAndArrow(record, lastRecordsInMemory);

					lastElementsAdded++;
				}
			}
			Log.i("Medtronic", "Fill next expected");
			expectedSensorSortNumber = readData[firstMeasureByte + 3];
		}

		// I must recalculate next message!!!!
		synchronized (expectedSensorSortNumberLock) {
			expectedSensorSortNumber = calculateNextSensorSortNameFrom(1,
					expectedSensorSortNumber);
		}

		SharedPreferences.Editor editor = settings.edit();
		editor.putString("expectedSensorSortNumber",
				HexDump.toHexString(expectedSensorSortNumber));
		editor.putInt("calibrationStatus", calibrationStatus);
		lastSensorValueDate = d.getTime();
		editor.putLong("lastSensorValueDate", lastSensorValueDate);
		editor.apply();
	 	writeLocalCSV(previousRecord, context);

		Log.i(TAG, "sensorprocessed end expected "
				+ HexDump.toHexString(expectedSensorSortNumber));

	}

	/**
	 * Checks if the message received is the expected redundant message.
	 * 
	 * @param sortID
	 * @return true, if is the redundant message
	 */
	private boolean isSensorRepeatedMessage(byte sortID) {
		return sortID == (expectedSensorSortNumber | 0x1);
	}

	/**
	 * Sensor messages are 1 byte - cycling through 16 values
	 * 00, 01, 10, 11, 20, 21 .. 70, 71
	 * The payload of message {n}0 is repeated in message {n}1 - eg. 20 and 21 are same data
	 * @return next order to be expected
	 */
	private byte calculateNextSensorSortNameFrom(int shift,
			byte expectedSensorSortNumber) {
		// sendMessageToUI("calculating FROM "+HexDump.toHexString(expectedSensorSortNumber),
		// false);
		if (expectedSensorSortNumber < 0) return (byte) 0xff;

		while (shift > 0) {
			if ((expectedSensorSortNumber & (byte) 1) != 0) {
				expectedSensorSortNumber += (byte) 0x10;
				expectedSensorSortNumber &= 0x70;
			} else {
				expectedSensorSortNumber |= 0x01;
			}
			--shift;
		}
		return expectedSensorSortNumber;
	}

	private int transformSequenceToIndex(byte aux) {
		int seq = aux >> 4;
		return (seq == 0) ? 8 : seq;
	}

	/**
	 * This function checks if the measure index is between a range of indexes
	 * previously stored.
	 * 
	 * @param measureIndex
	 *            , index to check
	 * @param range
	 *            ,
	 * @return true if the measure index is between a range of indexes
	 *         previously stored.
	 */
	private boolean isSensorMeasureInRange(byte measureIndex, byte[] range) {
		byte minRange = range[0];
		byte maxRange = range[1];
		if (HexDump.unsignedByte(maxRange) < HexDump.unsignedByte(minRange)) {
			return ((HexDump.unsignedByte(measureIndex) >= HexDump
					.unsignedByte(minRange)) && (HexDump
							.unsignedByte(measureIndex) <= HexDump
							.unsignedByte((byte) 0x71)))
							|| (HexDump.unsignedByte(measureIndex) <= HexDump
							.unsignedByte(maxRange))
							&& (HexDump.unsignedByte(measureIndex) >= HexDump
							.unsignedByte((byte) 0x00));
		} else {
			return (HexDump.unsignedByte(measureIndex) >= HexDump
					.unsignedByte(minRange))
					&& (HexDump.unsignedByte(measureIndex) <= HexDump
					.unsignedByte(maxRange));
		}
	}


	/**
	 * This method calculates the date of the sensor readings
	 * 
	 * @param record
	 *            , current sensor reading
	 * @param initTime
	 *            , time of the first (most actual) reading in this row
	 * @param subtract
	 *            , index of this reading respectively the initTime reading.
	 *            Each increment subtracts 5 minutes to "initTime"
	 */
	public void setRecordDateHistoric(Record record, Date initTime, int subtract) {

		long milliseconds = initTime.getTime();

		if (subtract > 0) {
			milliseconds -= subtract * MedtronicConstants.TIME_5_MIN_IN_MS;// record
			// was
			// read
			// (subtract
			// *
			// 5
			// minutes) before the initTime
		}

		long timeAdd = milliseconds;

		/*
		 * TimeZone tz = TimeZone.getDefault();
		 * 
		 * if (!tz.inDaylightTime(new Date())) timeAdd = timeAdd - 3600000L;
		 */
		Date display = new Date(timeAdd);

		if (record instanceof MedtronicSensorRecord) {
			((MedtronicSensorRecord) record).setDate(display);
		}
	}

	/**
	 * This method checks if a calibration is valid.
	 * 
	 * @param difference
	 * @param currentMeasure
	 * @param currentIndex
	 */
	private void calculateCalibration(long difference, float currentMeasure,
			byte currentIndex) {
		if (difference >= MedtronicConstants.TIME_15_MIN_IN_MS
				&& difference < MedtronicConstants.TIME_20_MIN_IN_MS) {
			if (isSensorMeasureInRange(currentIndex,
					expectedSensorSortNumberForCalibration)) {
				isCalibrating = false;
				calibrationStatus = MedtronicConstants.CALIBRATED;
				calibrationIsigValue = currentMeasure;
				SharedPreferences.Editor editor = settings.edit();
				calibrationFactor = lastGlucometerValue / calibrationIsigValue;
				editor.remove("expectedSensorSortNumberForCalibration0");
				editor.remove("expectedSensorSortNumberForCalibration1");
				editor.putFloat("calibrationFactor", calibrationFactor);
				editor.putInt("calibrationStatus",
						calibrationStatus);
				editor.apply();
			} else {
				if (calibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION
						&& currentIndex != expectedSensorSortNumber) {
					calibrationStatus = MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS;
					isCalibrating = false;
				} else {
					calibrationStatus = MedtronicConstants.WITHOUT_ANY_CALIBRATION;
				}
				SharedPreferences.Editor editor = settings.edit();
				editor.remove("expectedSensorSortNumberForCalibration0");
				editor.remove("expectedSensorSortNumberForCalibration1");
				editor.apply();
			}
		} else if (difference >= MedtronicConstants.TIME_20_MIN_IN_MS) {
			if (isSensorMeasureInRange(currentIndex,
					expectedSensorSortNumberForCalibration)) {
				calibrationStatus = MedtronicConstants.CALIBRATED_IN_15MIN;
				calibrationIsigValue = currentMeasure;
				SharedPreferences.Editor editor = settings.edit();
				calibrationFactor = lastGlucometerValue / calibrationIsigValue;
				editor.remove("expectedSensorSortNumberForCalibration0");
				editor.remove("expectedSensorSortNumberForCalibration1");
				editor.putFloat("calibrationFactor", calibrationFactor);
				editor.putInt("calibrationStatus",
						calibrationStatus);
				editor.apply();
			} else {
				if (calibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION)
					calibrationStatus = MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS;
				else {
					calibrationStatus = MedtronicConstants.WITHOUT_ANY_CALIBRATION;
				}
				SharedPreferences.Editor editor = settings.edit();
				editor.remove("expectedSensorSortNumberForCalibration0");
				editor.remove("expectedSensorSortNumberForCalibration1");
				editor.apply();
			}
			isCalibrating = false;
		} else {
			if (isCalibrating){
				if (difference < MedtronicConstants.TIME_5_MIN_IN_MS) {
					calibrationStatus = MedtronicConstants.CALIBRATING;
				} else if (difference >= MedtronicConstants.TIME_5_MIN_IN_MS
						&& difference <= MedtronicConstants.TIME_15_MIN_IN_MS)
					calibrationStatus = MedtronicConstants.CALIBRATING2;
				else
					calibrationStatus = MedtronicConstants.CALIBRATING;
			}else{
				if (calibrationStatus != MedtronicConstants.WITHOUT_ANY_CALIBRATION)
					calibrationStatus = MedtronicConstants.LAST_CALIBRATION_FAILED_USING_PREVIOUS;
				else {
					calibrationStatus = MedtronicConstants.WITHOUT_ANY_CALIBRATION;
				}
				SharedPreferences.Editor editor = settings.edit();
				editor.remove("expectedSensorSortNumberForCalibration0");
				editor.remove("expectedSensorSortNumberForCalibration1");
				editor.putInt("calibrationStatus",
						calibrationStatus);
				editor.apply();
			}
		}
	}

	/**
	 * Function which helps to calculate the difference of Glucose.
	 * 
	 * @param size
	 *            , amount of records to use (aprox. 5 min between records)
	 * @param list
	 *            , list of records.
	 * @return, Total Glucose variation.
	 */
	public Float getGlucoseDifferentialIn(int size, CircleList<Record> list) {
		List<Record> auxList = list.getListFromTail(size);
		SimpleDateFormat formatter = new SimpleDateFormat(
				"MM/dd/yyyy hh:mm:ss a", Locale.getDefault());
		if (auxList.size() == size) {
			Log.d(TAG, "I Have the correct size");
			for (int i = 1; i < size; i++) {
				if (!(auxList.get(i) instanceof MedtronicSensorRecord)) {
					Log.d(TAG, "but not the correct records");
					return null;
				}
			}
			float diff = 0;
			long dateDif = 0;
			for (int i = 1; i < size; i++) {
				Log.d(TAG, "Start calculate diff");
				MedtronicSensorRecord prevRecord = (MedtronicSensorRecord) auxList
						.get(i - 1);
				MedtronicSensorRecord record = (MedtronicSensorRecord) auxList
						.get(i);
				Date prevDate = null;
				Date date = null;

				prevDate = prevRecord.getDate();
				date = record.getDate();
				dateDif += (prevDate.getTime() - date.getTime());

				float prevRecordValue = 0;
				float recordValue = 0;

				prevRecordValue = prevRecord.getBGValue();
				recordValue = record.getBGValue();


				if (prevRecordValue > 0 && recordValue <= 0) {
					Log.d(TAG, "AdjustRecordValue prev " + prevRecordValue
							+ " record " + recordValue);
					recordValue = prevRecordValue;
				}
				diff += prevRecordValue - recordValue;
				Log.d(TAG, "VALUEDIFF " + diff);
			}
			if (dateDif > MedtronicConstants.TIME_20_MIN_IN_MS) {
				Log.d(TAG, "EXIT BY TIME ");
				return null;
			} else {
				Log.d(TAG, "CORRECT EXIT ");
				return diff;
			}
		} else {
			Log.d(TAG, "I DO NOT Have the correct size " + auxList.size());
			return null;
		}
	}

	/**
	 * Function to calculate ISIG value
	 * 
	 * @param value
	 *            , Raw Value
	 * @param adjustment
	 *            ,
	 * @return ISIG value
	 */
	public float calculateISIG(int value, short adjustment) {
		float isig = (float) value
				/ (MedtronicConstants.SENSOR_CONVERSION_CONSTANT_VALUE - ((float) value * MedtronicConstants.SENSOR_CONVERSION_CONSTANT_VALUE2));
		isig += ((float) adjustment * (float) value * (MedtronicConstants.SENSOR_CONVERSION_CONSTANT_VALUE3 + (MedtronicConstants.SENSOR_CONVERSION_CONSTANT_VALUE4
				* (float) value / (float) MedtronicConstants.SENSOR_CONVERSION_CONSTANT_VALUE5)));
		return isig;
	}

	/**
	 * This function calculates the SVG to upload applying a filter to the
	 * Unfiltered glucose data
	 *
	 */
	public int applyFilterToRecord(MedtronicSensorRecord currentRecord) {
		/*
		 * if (auxList.size() >= 2) {
		 * 
		 * if (!(auxList.get(0) instanceof MedtronicSensorRecord)) return -1;
		 * MedtronicSensorRecord record = (MedtronicSensorRecord) auxList
		 * .get(0); MedtronicSensorRecord record2 = (MedtronicSensorRecord)
		 * auxList .get(1); return (int) ((currentRecord.unfilteredGlucose *
		 * glucoseFilter[0]) + (record.unfilteredGlucose * glucoseFilter[1]) +
		 * (record2.unfilteredGlucose * glucoseFilter[2])); } else if
		 * (auxList.size() == 1) { MedtronicSensorRecord record =
		 * (MedtronicSensorRecord) auxList .get(0); return (int)
		 * ((currentRecord.unfilteredGlucose * glucoseFilter[0]) +
		 * (currentRecord.unfilteredGlucose * glucoseFilter[1]) +
		 * (record.unfilteredGlucose * glucoseFilter[2])); }else{ return (int)
		 * ((currentRecord.unfilteredGlucose * glucoseFilter[0]) +
		 * (currentRecord.unfilteredGlucose * glucoseFilter[1])+
		 * (currentRecord.unfilteredGlucose * glucoseFilter[2])); }
		 */
		return (int) currentRecord.unfilteredGlucose;

	}


	/**
	 * This function tries to calculate the trend of the glucose values.
	 * 
	 * @param record
	 * @param list
	 */
	public void calculateTrendAndArrow(MedtronicSensorRecord record,
			CircleList<Record> list) {
		String trend = "Not Calculated";
		String trendA = "--X";
		Float diff = getGlucoseDifferentialIn(3, list);// most Recent first
		if (diff != null) {
			diff /= 5f;
			diff *= 0.0555f;// convierto a mmol/l
			int trendArrow = 0;
			if (diff >= -0.06f && diff <= 0.06f)
				trendArrow = 4;
			else if ((diff > 0.06f) && (diff <= 0.11f)) {
				trendArrow = 3;
			} else if ((diff < -0.06f) && (diff >= -0.11f)) {
				trendArrow = 5;
			} else if ((diff > 0.11f) && (diff <= 0.17f)) {
				trendArrow = 2;
			} else if ((diff < -0.11f) && (diff >= -0.17f)) {
				trendArrow = 6;
			} else if ((diff > 0.17f)) {
				trendArrow = 1;
			} else if ((diff < -0.17f)) {
				trendArrow = 7;
			} else {
				trendArrow = 0;
			}

			switch (trendArrow) {
			case (0):
				trendA = "\u2194";
			trend = "NONE";
			break;
			case (1):
				trendA = "\u21C8";
			trend = "DoubleUp";
			break;
			case (2):
				trendA = "\u2191";
			trend = "SingleUp";
			break;
			case (3):
				trendA = "\u2197";
			trend = "FortyFiveUp";
			break;
			case (4):
				trendA = "\u2192";
			trend = "Flat";
			break;
			case (5):
				trendA = "\u2198";
			trend = "FortyFiveDown";
			break;
			case (6):
				trendA = "\u2193";
			trend = "SingleDown";
			break;
			case (7):
				trendA = "\u21CA";
			trend = "DoubleDown";
			break;
			case (8):
				trendA = "\u2194";
			trend = "NOT COMPUTABLE";
			break;
			case (9):
				trendA = "\u2194";
			trend = "RATE OUT OF RANGE";
			break;
			}
		} else {
			trendA = "\u2194";
			trend = "RATE OUT OF RANGE";
		}

		record.trend = trend;
		record.trendArrow = trendA;
	}

	/**
     * Sends an error message to be printed in the display (DEBUG) if it is repeated, It is not printed again. If UI is not visible, It will launch a pop-up message.
     * @param valuetosend
     */
	private void sendErrorMessageToUI(String valuetosend) {
		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss - ");
		//get current date time with Date()
		Date date = new Date();
		valuetosend = dateFormat.format(date) + valuetosend;
		Log.e(TAG, valuetosend);
		// log.debug("MedtronicReader Sends to UI "+valuetosend);

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
		} 
	}
	
	
	/**
	 * Class to manage the circular aspect of the sensor readings
	 * 
	 * @author lmmarguenda
	 * 
	 * @param <E>
	 */
	class CircleList<E> {
		private int size;
		private int capacity;
		private int endOffset;
		private int startOffset;
		ArrayList<E> list = new ArrayList<E>();
		private Object listLock = new Object();

		/**
		 * Constructor
		 * 
		 * @param capacity
		 */
		public CircleList(int capacity) {
			size = 0;
			this.capacity = capacity;
			endOffset = 0;
			startOffset = 0;
			list = new ArrayList<E>();
		}

		/**
		 * add
		 * 
		 * @param item
		 */
		public void add(E item) {
			synchronized (listLock) {
				if (endOffset == capacity) {
					endOffset = 0;
					startOffset = 1;
				}
				if (endOffset >= list.size())
					list.add(endOffset, item);
				else
					list.set(endOffset, item);
				endOffset++;
				if (endOffset <= startOffset)
					startOffset++;
				if (startOffset == capacity)
					startOffset = 0;
				size = list.size();
			}
		}


		/**
		 * @param size
		 *            , maximum number of elements to get.
		 * @return a list sorted from the "endOffset" to the "startOffset".
		 */
		public List<E> getListFromTail(int size) {
			List<E> result = new ArrayList<E>();
			List<E> aux = null;
			int auxEndOffset = 0;
			int auxStartOffset = 0;
			synchronized (listLock) {
				auxEndOffset = endOffset;
				auxStartOffset = startOffset;
				aux = new ArrayList<E>(list);

			}
			int auxSize = size;
			if (auxSize > aux.size())
				auxSize = aux.size();

			if (auxEndOffset > auxStartOffset) {
				for (int i = auxEndOffset - 1; i >= auxStartOffset
						&& auxSize > 0; i--) {
					result.add(aux.get(i));
					auxSize--;
				}
			} else {
				for (int i = auxEndOffset - 1; i >= 0 && auxSize > 0; i--) {
					result.add(aux.get(i));
					auxSize--;
				}
				if (auxSize > 0) {
					for (int i = capacity - 1; i >= auxStartOffset
							&& auxSize > 0; i--) {
						result.add(aux.get(i));
						auxSize--;
					}
				}
			}
			return result;
		}

		/**
		 * 
		 * @return items allocated.
		 */
		public int size() {
			synchronized (listLock) {
				return size;
			}
		}
	}

	/**
	 * Runnable to check how old is the last calibration, and to manage the time
	 * out of the current calibration process
	 * 
	 * @author lmmarguenda
	 * 
	 */
	class CalibrationStatusChecker implements Runnable {
		public Handler mHandlerReviewParameters = null;

		public CalibrationStatusChecker(Handler mHandlerReviewParameters) {
			this.mHandlerReviewParameters = mHandlerReviewParameters;
		}

		public void run() {
			checkCalibrationOutOfTime();
			mHandlerReviewParameters.postDelayed(this,
					MedtronicConstants.TIME_5_MIN_IN_MS);
		}
	}


	private void writeLocalCSV(MedtronicSensorRecord mostRecentData,
                       Context context) {

               // Write EGV Binary of last (most recent) data
               try {
                       if (mostRecentData == null || mostRecentData.getBGValue() == 0)
                               Log.d(TAG, "writeLocalCSV SAVING  EMPTY!!");
                       else
                               Log.d(TAG, "writeLocalCSV SAVING --> " + mostRecentData.getBGValue());
                       ObjectOutputStream oos = new ObjectOutputStream(
					                                       new FileOutputStream(new File(context.getFilesDir(),
					                                                       "save.bin"))); // Select where you wish to save the
                       // file...
                       oos.writeObject(mostRecentData); // write the class as an 'object'
                       oos.flush(); // flush the stream to insure all of the information
                       // was written to 'save.bin'
                       oos.close();// close the stream
               } catch (Exception e) {
                       Log.e(TAG, "write to OutputStream failed", e);
               }
       }

}
