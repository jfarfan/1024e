/*
 * Copyright 2013 - 2014 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.helper.Checksum;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class ApelProtocolDecoder extends BaseProtocolDecoder {

    private long lastIndex;
    private long newIndex;

    public ApelProtocolDecoder(ApelProtocol protocol) {
        super(protocol);
    }

    public static final short MSG_NULL = 0;
    public static final short MSG_REQUEST_TRACKER_ID = 10;
    public static final short MSG_TRACKER_ID = 11;
    public static final short MSG_TRACKER_ID_EXT = 12;
    public static final short MSG_DISCONNECT = 20;
    public static final short MSG_REQUEST_PASSWORD = 30;
    public static final short MSG_PASSWORD = 31;
    public static final short MSG_REQUEST_STATE_FULL_INFO = 90;
    public static final short MSG_STATE_FULL_INFO_T104 = 92;
    public static final short MSG_REQUEST_CURRENT_GPS_DATA = 100;
    public static final short MSG_CURRENT_GPS_DATA = 101;
    public static final short MSG_REQUEST_SENSORS_STATE = 110;
    public static final short MSG_SENSORS_STATE = 111;
    public static final short MSG_SENSORS_STATE_T100 = 112;
    public static final short MSG_SENSORS_STATE_T100_4 = 113;
    public static final short MSG_REQUEST_LAST_LOG_INDEX = 120;
    public static final short MSG_LAST_LOG_INDEX = 121;
    public static final short MSG_REQUEST_LOG_RECORDS = 130;
    public static final short MSG_LOG_RECORDS = 131;
    public static final short MSG_EVENT = 141;
    public static final short MSG_TEXT = 150;
    public static final short MSG_ACK_ALARM = 160;
    public static final short MSG_SET_TRACKER_MODE = 170;
    public static final short MSG_GPRS_COMMAND = 180;

    private void sendSimpleMessage(Channel channel, short type) {
        ChannelBuffer request = ChannelBuffers.directBuffer(ByteOrder.LITTLE_ENDIAN, 8);
        request.writeShort(type);
        request.writeShort(0);
        request.writeInt(Checksum.crc32(request.toByteBuffer(0, 4)));
        channel.write(request);
    }

    private void requestArchive(Channel channel) {
        if (lastIndex == 0) {
            lastIndex = newIndex;
        } else if (newIndex > lastIndex) {
            ChannelBuffer request = ChannelBuffers.directBuffer(ByteOrder.LITTLE_ENDIAN, 14);
            request.writeShort(MSG_REQUEST_LOG_RECORDS);
            request.writeShort(6);
            request.writeInt((int) lastIndex);
            request.writeShort(512);
            request.writeInt(Checksum.crc32(request.toByteBuffer(0, 10)));
            channel.write(request);
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;
        int type = buf.readUnsignedShort();
        boolean alarm = (type & 0x8000) != 0;
        type = type & 0x7FFF;
        buf.readUnsignedShort(); // length

        if (alarm) {
            sendSimpleMessage(channel, MSG_ACK_ALARM);
        }

        if (type == MSG_TRACKER_ID) {
            return null; // unsupported authentication type
        }

        if (type == MSG_TRACKER_ID_EXT) {

            buf.readUnsignedInt(); // id
            int length = buf.readUnsignedShort();
            buf.skipBytes(length);
            length = buf.readUnsignedShort();
            identify(buf.readBytes(length).toString(StandardCharsets.US_ASCII), channel, remoteAddress);

        } else if (type == MSG_LAST_LOG_INDEX) {

            long index = buf.readUnsignedInt();
            if (index > 0) {
                newIndex = index;
                requestArchive(channel);
            }

        } else if (hasDeviceId()
                && (type == MSG_CURRENT_GPS_DATA || type == MSG_STATE_FULL_INFO_T104 || type == MSG_LOG_RECORDS)) {

            List<Position> positions = new LinkedList<>();

            int recordCount = 1;
            if (type == MSG_LOG_RECORDS) {
                recordCount = buf.readUnsignedShort();
            }

            for (int j = 0; j < recordCount; j++) {
                Position position = new Position();
                position.setProtocol(getProtocolName());
                position.setDeviceId(getDeviceId());

                int subtype = type;
                if (type == MSG_LOG_RECORDS) {
                    position.set(Position.KEY_ARCHIVE, true);
                    lastIndex = buf.readUnsignedInt() + 1;
                    position.set(Position.KEY_INDEX, lastIndex);

                    subtype = buf.readUnsignedShort();
                    if (subtype != MSG_CURRENT_GPS_DATA && subtype != MSG_STATE_FULL_INFO_T104) {
                        buf.skipBytes(buf.readUnsignedShort());
                        continue;
                    }
                    buf.readUnsignedShort(); // length
                }

                position.setTime(new Date(buf.readUnsignedInt() * 1000));
                position.setLatitude(buf.readInt() * 180.0 / 0x7FFFFFFF);
                position.setLongitude(buf.readInt() * 180.0 / 0x7FFFFFFF);

                if (subtype == MSG_STATE_FULL_INFO_T104) {
                    int speed = buf.readUnsignedByte();
                    position.setValid(speed != 255);
                    position.setSpeed(UnitsConverter.knotsFromKph(speed));
                    position.set(Position.KEY_HDOP, buf.readByte());
                } else {
                    int speed = buf.readShort();
                    position.setValid(speed != -1);
                    position.setSpeed(UnitsConverter.knotsFromKph(speed * 0.01));
                }

                position.setCourse(buf.readShort() * 0.01);
                position.setAltitude(buf.readShort());

                if (subtype == MSG_STATE_FULL_INFO_T104) {

                    position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
                    position.set(Position.KEY_GSM, buf.readUnsignedByte());
                    position.set(Position.KEY_EVENT, buf.readUnsignedShort());
                    position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());
                    position.set(Position.KEY_INPUT, buf.readUnsignedByte());
                    position.set(Position.KEY_OUTPUT, buf.readUnsignedByte());

                    for (int i = 1; i <= 8; i++) {
                        position.set(Position.PREFIX_ADC + i, buf.readUnsignedShort());
                    }

                    position.set(Position.PREFIX_COUNT + 1, buf.readUnsignedInt());
                    position.set(Position.PREFIX_COUNT + 2, buf.readUnsignedInt());
                    position.set(Position.PREFIX_COUNT + 3, buf.readUnsignedInt());
                }

                positions.add(position);
            }

            buf.readUnsignedInt(); // crc

            if (type == MSG_LOG_RECORDS) {
                requestArchive(channel);
            } else {
                sendSimpleMessage(channel, MSG_REQUEST_LAST_LOG_INDEX);
            }

            return positions;
        }

        return null;
    }

}
