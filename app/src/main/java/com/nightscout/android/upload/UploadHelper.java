package com.nightscout.android.upload;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;

import com.nightscout.android.dexcom.DexcomG4Activity;
import com.nightscout.android.dexcom.EGVRecord;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class UploadHelper extends AsyncTask<Record, Integer, Long> {


    private static final String TAG = "UploadHelper";
    private SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss aa", Locale.getDefault());
    private static final int READ_TIMEOUT = 30 * 1000;
    private static final int CONNECTION_TIMEOUT = 30 * 1000;
    private String baseURLSettings;


    public UploadHelper(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        baseURLSettings = prefs.getString("API Base URL", "");
    }


    /**
     * @return constant String to identify the selected Device
     */
    private String getSelectedDeviceName() {
        return "Medtronic_CGM";

    }

    /**
     * doInBackground
     */
    protected Long doInBackground(Record... records) {

        try {

            long start = System.currentTimeMillis();
            Log.i(TAG, String.format("Starting upload of %s record using a REST API", records.length));
            doRESTUpload(records);
            Log.i(TAG, String.format("Finished upload of %s record using a REST API in %s ms", records.length, System.currentTimeMillis() - start));

        } catch (Exception e) {
            Log.e(TAG, "ERROR uploading data!!!!!", e);
        }

        return 1L;
    }

    protected void onPostExecute(Long result) {
        super.onPostExecute(result);
        Log.i(TAG, "Post execute, Result: " + result + ", Status: FINISHED");
    }

    private void doRESTUpload(Record... records) {

        ArrayList<String> baseURIs = new ArrayList<>();

        try {
            for (String baseURLSetting : baseURLSettings.split(" ")) {
                String baseURL = baseURLSetting.trim();
                if (baseURL.isEmpty()) continue;
                baseURIs.add(baseURL + (baseURL.endsWith("/") ? "" : "/"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to process API Base URL setting: " + baseURLSettings, e);
            return;
        }

        for (String baseURI : baseURIs) {
            try {
                doRESTUploadTo(baseURI, records);
            } catch (Exception e) {
                Log.e(TAG, "Unable to do REST API Upload to: " + baseURI, e);
            }
        }
    }


    private void doRESTUploadTo(String baseURI, Record[] records) throws Exception {

        if (!baseURI.endsWith("/v1/"))
            throw new Exception("REST API URL must start end with /v1/");

        try {

            String baseURL = null;
            String secret = null;
            String[] uriParts = baseURI.split("@");
            String error = null;

            //noinspection IfCanBeSwitch
            if (uriParts.length == 1) {
                error = "Passphrase is required in REST API URL";
            } else if (uriParts.length == 2) {
                secret = uriParts[0]; // Allows for https://PASS@website.azurewe.../api/v1/ AND for PASS@https://website.azurewe.../api/v1/
                baseURL = uriParts[1];

                if (secret.contains("http")) { // fix up if it is https://PASS@
                    if (secret.contains("https")) {
                        baseURL = "https://" + baseURL;
                    } else {
                        baseURL = "http://" + baseURL;
                    }
                    String[] uriParts2 = secret.split("//");
                    secret = uriParts2[1];
                }

                if (secret.isEmpty()) error = "Secret not read correctly from URL " + baseURI;
            } else {
                error = String.format("Unexpected baseURI: %s, uriParts.length: %s", baseURI, uriParts.length);
            }

            if (error != null) {
                throw new Exception(error);
            }

            postDeviceStatus(baseURL);

            for (Record record : records) {

                String postURL = determineUrlForRecord(baseURL, record);
                postRecord(postURL, secret, record);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to post data", e);
        }

    }

    private String determineUrlForRecord(String baseURL, Record record) {

       String postURL;
        if (record instanceof GlucometerRecord) {
            postURL =  baseURL + "entries";
        } else if (record instanceof MedtronicPumpRecord) {
            postURL = baseURL + "deviceentries";
        } else {
            postURL = baseURL + "entries";
        }
        Log.i(TAG, "postURL: " + postURL);
        return postURL;
    }

    private void postRecord(String postURL, String secret, Record record) throws NoSuchAlgorithmException, IOException {

        HttpURLConnection urlConnection = null;
        OutputStream os = null;
        BufferedWriter writer = null;

        try {
            urlConnection = (HttpURLConnection) new URL(postURL).openConnection();
            setPostConnectionProperties(urlConnection);
            setAuthenticationToken(secret, urlConnection);

            String jsonString = convertRecordToJsonString(record);
            if (jsonString == null) {
                return;
            }

            try {
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setRequestProperty("Content-type", "application/json");

                // stream the body
                os = urlConnection.getOutputStream();
                writer = new BufferedWriter(
                        new OutputStreamWriter(os, StandardCharsets.UTF_8));
                writer.write(jsonString);

                writer.flush();
            } catch (Exception e) {
                Log.w(TAG, "Unable to post data to: '" + postURL + "'", e);
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }

            if (urlConnection != null) {
                try {
                    Log.i(TAG, "Post to Url: '" + postURL + "' returned Http-Status " + urlConnection.getResponseCode());
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
                urlConnection.disconnect();
            }
        }
    }

    private void setAuthenticationToken(String secret, HttpURLConnection urlConnection) throws NoSuchAlgorithmException {

        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        digest.update(bytes, 0, bytes.length);
        bytes = digest.digest();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        String token = sb.toString();

        urlConnection.setRequestProperty("api-secret", token);
    }

    private String convertRecordToJsonString(Record record) {

        JSONObject json = new JSONObject();
        try {
            populateV1APIEntry(json, record);

        } catch (Exception e) {
            Log.w(TAG, "Unable to populate entry.", e);
            return null;
        }
        String jsonString = json.toString();

        Log.i(TAG, "JSON: " + jsonString);
        return jsonString;
    }

    private void postDeviceStatus(String baseURL) {

        HttpURLConnection urlConnection = null;
        OutputStream os = null;
        BufferedWriter writer = null;
        String devicestatusURL = "";
        try {

            devicestatusURL = baseURL + "devicestatus";

            Log.i(TAG, "devicestatusURL: " + devicestatusURL);

            JSONObject json = new JSONObject();
            json.put("uploaderBattery", DexcomG4Activity.batLevel);
            String jsonString = json.toString();

            urlConnection = (HttpURLConnection) new URL(devicestatusURL).openConnection();
            setPostConnectionProperties(urlConnection);

            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestProperty("Content-type", "application/json");

            // stream the body
            os = urlConnection.getOutputStream();
            writer = new BufferedWriter(
                    new OutputStreamWriter(os, StandardCharsets.UTF_8));
            writer.write(jsonString);

            writer.flush();
        } catch (Exception e) {
            Log.w(TAG, "Could not send device status", e);

        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }

            if (urlConnection != null) {
                try {
                    Log.i(TAG, "Post to Url: '" + devicestatusURL + "' returned Http-Status " + urlConnection.getResponseCode());
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
                urlConnection.disconnect();
            }
        }

    }

    private void setPostConnectionProperties(HttpURLConnection urlConnection) throws ProtocolException {
        urlConnection.setReadTimeout(READ_TIMEOUT);
        urlConnection.setConnectTimeout(CONNECTION_TIMEOUT);
        urlConnection.setRequestMethod("POST");
        urlConnection.setDoInput(true);
        urlConnection.setDoOutput(true);
    }

    private void populateV1APIEntry(JSONObject json, Record oRecord) throws Exception {
        Date date = oRecord.getDate();
        json.put("date", date.getTime());
        json.put("dateString", DATE_FORMAT.format(date));
        if (oRecord instanceof GlucometerRecord) {
            json.put("gdValue", ((GlucometerRecord) oRecord).numGlucometerValue);
            json.put("device", getSelectedDeviceName());
            json.put("type", "mbg");
            json.put("mbg", ((GlucometerRecord) oRecord).numGlucometerValue);
        } else if (oRecord instanceof EGVRecord) {
            EGVRecord record = (EGVRecord) oRecord;
            json.put("device", getSelectedDeviceName());
            json.put("sgv", (int) record.getBGValue());
            json.put("direction", record.trend);
            if (oRecord instanceof MedtronicSensorRecord) {
                json.put("isig", ((MedtronicSensorRecord) record).isig);
                json.put("calibrationFactor", ((MedtronicSensorRecord) record).calibrationFactor);
                json.put("calibrationStatus", ((MedtronicSensorRecord) record).calibrationStatus);
                json.put("unfilteredGlucose", ((MedtronicSensorRecord) record).unfilteredGlucose);
                json.put("isCalibrating", ((MedtronicSensorRecord) record).isCalibrating);
            }
        } else if (oRecord instanceof MedtronicPumpRecord) {
            MedtronicPumpRecord pumpRecord = (MedtronicPumpRecord) oRecord;
            json.put("name", pumpRecord.getDeviceName());
            json.put("deviceId", pumpRecord.deviceId);
            json.put("insulinLeft", pumpRecord.insulinLeft);
            json.put("alarm", pumpRecord.alarm);
            json.put("status", pumpRecord.status);
            json.put("temporaryBasal", pumpRecord.temporaryBasal);
            json.put("batteryStatus", pumpRecord.batteryStatus);
            json.put("batteryVoltage", pumpRecord.batteryVoltage);
            json.put("isWarmingUp", pumpRecord.isWarmingUp);
        }

    }

}
