package com.nightscout.android.upload;

import java.io.Serializable;
import java.util.Date;

public class Record implements Serializable {

	 private Date date;

	 public void setDate(Date d)
	 {
	 	date = d;
	 }

	 public Date getDate()
	 {
	 	return date;
	 }

	/**
	 * 
	 */
	private static final long serialVersionUID = -1381174446348390503L;
	

}
