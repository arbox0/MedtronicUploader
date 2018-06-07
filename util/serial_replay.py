#!/usr/bin/python
import re
import argparse
import serial
from datetime import datetime
from dateutil.parser import parse    

parser = argparse.ArgumentParser()
parser.add_argument('files', nargs='+', help = 'files to parse and send')
parser.add_argument('--speedup', default = 1, nargs='?', help = 'speed-up multiplier')

datem = '(\d\d\d\d-\d\d-\d\d \d\d:\d\d:\d\d) (?:\+|-)\d\d\d\d GMT'
readm = ' I MedtronicReader  :-1 a   READ (\d+) bytes:'
datam = ' I MedtronicReader  :-1 a   0x[0-9A-F]+(( [0-9A-F]+)+)'


args = parser.parse_args()

datastream = []

for file in args.files:
    with open(file) as f:
        content = f.readlines()

    content_iter = iter(content)

    start_time = None

    try:
        while True:
            line = content_iter.next()

            m = re.search(datem + readm,  line)
            if m:
                toread = int(m.group(2))
                when = parse(m.group(1))
                if not start_time:
                    start_time = when
                bytes = []
                while toread > len(bytes):
                    line = content_iter.next()

                    m = re.search(datem + datam, line)
                    if not m:
                        continue
                    data = m.group(2)

                    bytes.extend([int(x,16) for x in data.split()])

#                t = datetime.strptime(when, '%Y-%m-%d %H:%M:%S')

                datastream.append({'time' : (when - start_time).seconds,  'data' : bytes}) 

    except StopIteration:
        pass



ser = serial.Serial('/dev/ttyUSB0')
ser.baudrate = 57600

for packet in datastream:
    print packet['data']

    ser.write(bytearray(packet['data']))
