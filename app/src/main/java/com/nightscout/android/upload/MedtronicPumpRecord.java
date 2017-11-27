package com.nightscout.android.upload;

import java.io.Serializable;

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

	public boolean isWarmingUp = false;
	public MedtronicPumpRecord(){
    	this.deviceName = "Medtronic pump";
    }

}
