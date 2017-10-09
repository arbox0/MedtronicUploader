package com.nightscout.android.medtronic;

import java.util.LinkedList;

/**
 * Created by david on 30/09/17.
 */

public class TestUSBData {

    /*  Sensor Data

        00 - Message header
        01 - Payload size (excluding byte 0 and 1 positions)
        02 - device type
        04-06 - Sensor
        07 -
        07-length+2 - Payload
        07 - ?
        08 - ?
        09 - adjustment
        10 - expected 'sort number'
        11 - data value msb
        12 - data value lsb
        13 - previous value msb
        14 - previous value lsb
        15 - ??
        16 - ??
        17 - ??
        18 - ??
        19 - more previous readings .. to end
        last but two: - 0
        last two bytes = CRC of bytes 2 to start of CRC

     */

    static private LinkedList<Integer> sensorReadings = new LinkedList<Integer>();
    static private byte sequenceNumber = (byte) 0;

    static private void updateSequenceNumber() {
        if ((sequenceNumber & (byte) 1) != 0) {
            sequenceNumber += (byte) 0x10;
            sequenceNumber &= 0x70;
        }
        else {
            sequenceNumber |= 0x01;
        }
    }

    public static byte[] fakeSensorData(byte[] deviceID, int glucoseValue)
    {
        byte data[] = new byte[36];

        data[0] = (byte) 0x02;
        data[1] = (byte) (data.length - 2);

        data[2] = MedtronicConstants.MEDTRONIC_SENSOR2;
        data[3] = (byte) 0x0f;

        System.arraycopy(deviceID, 0, data, 4, deviceID.length);

        data[9] = (byte) 0x19; /* observed adjustment value */
        data[10] = (byte) (sequenceNumber & 0xff);
        updateSequenceNumber();
        sensorReadings.offerFirst(glucoseValue);
        int i = 11;
        for (int value : sensorReadings) {
            if (i ==15) i += 4; /* skip empty bits */
            if (i >= data.length - 3) break;
            data[i++] = (byte) (value >> 8);
            data[i++] = (byte) (value & 0xff);
        }

        int crc = crc16(data, 2, 32);
        data[34] = (byte) (crc >> 8);
        data[35] = (byte) (crc & 0xff);

        return data;
    }



    static public int crc16(byte[] bytes, int offset, int end) {
        int crc = 0xFFFF; // initial value
        int polynomial = 0x1021; // 0001 0000 0010 0001 (0, 5, 12)
        // byte[] testBytes = "123456789".getBytes("ASCII");
        for (int k = offset; k < end; ++k) {
            byte b = bytes[k];
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i) & 1) == 1);
                boolean c15 = ((crc >> 15 & 1) == 1);
                crc <<= 1;
                if (c15 ^ bit)
                    crc ^= polynomial;
            }
        }
        crc &= 0xffff;
        return crc;
    }

/*

public static void main(String args[]) {

    for (short[] s : TestUSBData.packets) {
        byte b[] = new byte[s.length];
        for (int i = 0; i < s.length; ++i)
            b[i] = (byte) s[i];

        for (int i = 0; i < 10; ++i)
            for (int j = b.length - 10; j < b.length; ++j)
                System.out.printf("%d %d %x\n", i, j + 1, crc16(b, i, j + 1));
    }
    for (int i = 0; i < 20; ++i) {
        updateSequenceNumber();
        System.out.printf("%x\n", (int) sequenceNumber);
    }

}
*/

}
