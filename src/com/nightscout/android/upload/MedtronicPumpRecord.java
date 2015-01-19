package com.nightscout.android.upload;

import java.io.Serializable;

import com.mongodb.DBObject;
import com.nightscout.android.medtronic.MedtronicConstants;

public class MedtronicPumpRecord extends DeviceRecord implements Serializable{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = -1857687174963206840L;
	public float insulinLeft = -1;
    public String status = "---";
    public String alarm = "---";
    public String temporaryBasal = "---";
    public String batteryStatus = "---";
    public String batteryVoltage = "---";
    public String model = "---";
    public String sRemoteControlID1 = "---";//Not used yet, I don not see the point of uploading this info.
    public String sRemoteControlID2 = "---";//Not used yet, I don not see the point of uploading this info.
    public String sRemoteControlID3 = "---";//Not used yet, I don not see the point of uploading this info.
    public String sParadigmLink1 = "---";//Not used yet, I don not see the point of uploading this info.
    public String sParadigmLink2 = "---";//Not used yet, I don not see the point of uploading this info.
    public String sParadigmLink3 = "---";//Not used yet, I don not see the point of uploading this info.
    public String sSensorID = "---";//Not used yet, I don not see the point of uploading this info.
    public boolean isWarmingUp = false;
	public void setWarmingUp(boolean isWarmingUp) {
		this.isWarmingUp = isWarmingUp;
	}
	
    public void setBatteryStatus(String batteryStatus) {
		this.batteryStatus = batteryStatus;
	}
	public void setBatteryVoltage(String batteryVoltage) {
		this.batteryVoltage = batteryVoltage;
	}
	public MedtronicPumpRecord(){
    	this.deviceName = "Medtronic pump";
    }
	public void setStatus(String status) {
		this.status = status;
	}

	public void setAlarm(String alarm) {
		this.alarm = alarm;
	}

	public void setTemporaryBasal(String temporaryBasal) {
		this.temporaryBasal = temporaryBasal;
	}

	public void setInsulinLeft(float insulinLeft) {
		this.insulinLeft = insulinLeft;
	}
	
	public void setModel(String model) {
		this.model = model;
	}

	public void setsRemoteControlID1(String sRemoteControlID1) {
		this.sRemoteControlID1 = sRemoteControlID1;
	}
	public void setsRemoteControlID2(String sRemoteControlID2) {
		this.sRemoteControlID2 = sRemoteControlID2;
	}
	public void setsRemoteControlID3(String sRemoteControlID3) {
		this.sRemoteControlID3 = sRemoteControlID3;
	}
	public void setsParadigmLink1(String sParadigmLink1) {
		this.sParadigmLink1 = sParadigmLink1;
	}
	public void setsParadigmLink2(String sParadigmLink2) {
		this.sParadigmLink2 = sParadigmLink2;
	}
	public void setsParadigmLink3(String sParadigmLink3) {
		this.sParadigmLink3 = sParadigmLink3;
	}
	public void setsSensorID(String sSensorID) {
		this.sSensorID = sSensorID;
	}
	public void mergeCurrentWithDBObject(DBObject previousRecord){
		if (!previousRecord.containsField("insulinLeft") || insulinLeft > 0){
			previousRecord.put("insulinLeft", insulinLeft);
		}
		if (!previousRecord.containsField("status") || !("---".equals(status))){
			previousRecord.put("status", status);
		}
		if (!previousRecord.containsField("alarm") || !("---".equals(alarm))){
			previousRecord.put("alarm", alarm);
		}
		if (!previousRecord.containsField("temporaryBasal") || !("---".equals(temporaryBasal))){
			previousRecord.put("temporaryBasal", temporaryBasal);
		}
		if (!previousRecord.containsField("batteryStatus") || !("---".equals(batteryStatus))){
			previousRecord.put("batteryStatus", batteryStatus);
		}
		if (!previousRecord.containsField("batteryVoltage") || !("---".equals(batteryVoltage))){
			previousRecord.put("batteryVoltage", batteryVoltage);
		}
		if (!previousRecord.containsField("model") || !("---".equals(model))){
			previousRecord.put("model", model);
		}
		if (!previousRecord.containsField("sRemoteControlID1") || !("---".equals(sRemoteControlID1))){
			previousRecord.put("sRemoteControlID1", sRemoteControlID1);
		}
		if (!previousRecord.containsField("sRemoteControlID2") || !("---".equals(sRemoteControlID2))){
			previousRecord.put("sRemoteControlID2", sRemoteControlID2);
		}
		if (!previousRecord.containsField("sRemoteControlID3") || !("---".equals(sRemoteControlID3))){
			previousRecord.put("sRemoteControlID3", sRemoteControlID3);
		}
		if (!previousRecord.containsField("sParadigmLink1") || !("---".equals(sParadigmLink1))){
			previousRecord.put("sParadigmLink1", sParadigmLink1);
		}
		if (!previousRecord.containsField("sParadigmLink2") || !("---".equals(sParadigmLink2))){
			previousRecord.put("sParadigmLink2", sParadigmLink2);
		}
		if (!previousRecord.containsField("sParadigmLink3") || !("---".equals(sParadigmLink3))){
			previousRecord.put("sParadigmLink3", sParadigmLink3);
		}
		if (!("---".equals(sSensorID)) || (!previousRecord.containsField("sSensorID"))){
			previousRecord.put("sSensorID", sSensorID);
		}
		previousRecord.put("isWarmingUp", isWarmingUp);
	}
	
	
}
