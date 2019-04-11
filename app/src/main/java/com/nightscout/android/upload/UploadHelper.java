package com.nightscout.android.upload;

import java.security.MessageDigest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONObject;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;


import com.nightscout.android.dexcom.DexcomG4Activity;
import com.nightscout.android.dexcom.EGVRecord;
import com.nightscout.android.medtronic.MedtronicConstants;

public class UploadHelper extends AsyncTask<Record, Integer, Long> {


    private static final String TAG = "UploadHelper";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss aa", Locale.getDefault());
    private static final int SOCKET_TIMEOUT = 60 * 1000;
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
            Log.e(TAG,"ERROR uploading data!!!!!", e);
        }


        return 1L;
    }

    protected void onPostExecute(Long result) {
        super.onPostExecute(result);
        Log.i(TAG, "Post execute, Result: " + result + ", Status: FINISHED");

    }

    private void doRESTUpload(Record... records) {

        ArrayList<String> baseURIs = new ArrayList<String>();

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
        int apiVersion = 1;
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

            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setSoTimeout(params, SOCKET_TIMEOUT);
            HttpConnectionParams.setConnectionTimeout(params, CONNECTION_TIMEOUT);

            DefaultHttpClient httpclient = new DefaultHttpClient(params);

            postDeviceStatus(baseURL, httpclient);


            for (Record record : records) {
                String postURL = baseURL;
                if (record instanceof GlucometerRecord) {
                    postURL += "entries";
                } else if (record instanceof MedtronicPumpRecord) {
                    postURL += "deviceentries";
                } else {
                    postURL += "entries";
                }
                Log.i(TAG, "postURL: " + postURL);

                HttpPost post = new HttpPost(postURL);

                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] bytes = secret.getBytes("UTF-8");
                digest.update(bytes, 0, bytes.length);
                bytes = digest.digest();
                StringBuilder sb = new StringBuilder(bytes.length * 2);
                for (byte b : bytes) {
                    sb.append(String.format("%02x", b & 0xff));
                }
                String token = sb.toString();
                post.setHeader("api-secret", token);

                JSONObject json = new JSONObject();

                try {
                    populateV1APIEntry(json, record);

                } catch (Exception e) {
                    Log.w(TAG, "Unable to populate entry, apiVersion: " + apiVersion, e);
                    continue;
                }

                String jsonString = json.toString();

                Log.i(TAG, "DEXCOM JSON: " + jsonString);

                try {
                    StringEntity se = new StringEntity(jsonString);
                    post.setEntity(se);
                    post.setHeader("Accept", "application/json");
                    post.setHeader("Content-type", "application/json");

                    ResponseHandler responseHandler = new BasicResponseHandler();
                    httpclient.execute(post, responseHandler);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to post data to: '" + post.getURI().toString() + "'", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to post data", e);
        }

    }


    private void postDeviceStatus(String baseURL, DefaultHttpClient httpclient) {
        try {

            String devicestatusURL = baseURL + "devicestatus";

            Log.i(TAG, "devicestatusURL: " + devicestatusURL);

            JSONObject json = new JSONObject();
            json.put("uploaderBattery", DexcomG4Activity.batLevel);
            String jsonString = json.toString();

            HttpPost post = new HttpPost(devicestatusURL);
            StringEntity se = new StringEntity(jsonString);
            post.setEntity(se);
            post.setHeader("Accept", "application/json");
            post.setHeader("Content-type", "application/json");

            ResponseHandler responseHandler = new BasicResponseHandler();
            httpclient.execute(post, responseHandler);
        } catch (Exception e) {
            Log.w(TAG, "Could not send device status", e);

        }

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
