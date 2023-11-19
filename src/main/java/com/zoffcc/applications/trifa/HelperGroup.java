package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.zoffcc.applications.trifa.HelperFiletransfer.get_incoming_filetransfer_local_filename;
import static com.zoffcc.applications.trifa.HelperFiletransfer.save_group_incoming_file;
import static com.zoffcc.applications.trifa.HelperGeneric.getHexArray;
import static com.zoffcc.applications.trifa.MainActivity.*;
import static com.zoffcc.applications.trifa.TRIFAGlobals.*;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.ToxVars.*;

public class HelperGroup {

    private static final String TAG = "trifa.Hlp.Group";

    public static byte[] hex_to_bytes(String s)
    {
        int len = s.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2)
        {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }

        return data;
    }

    public static long tox_group_by_groupid__wrapper(String group_id_string)
    {
        ByteBuffer group_id_buffer = ByteBuffer.allocateDirect(GROUP_ID_LENGTH);
        byte[] data = hex_to_bytes(group_id_string.toUpperCase());
        group_id_buffer.put(data);
        group_id_buffer.rewind();

        long res = tox_group_by_chat_id(group_id_buffer);
        if (res == UINT32_MAX_JAVA)
        {
            return -1;
        }
        else if (res < 0)
        {
            return -1;
        }
        else
        {
            return res;
        }
    }

    public static String tox_group_by_groupnum__wrapper(long groupnum)
    {
        try
        {
            ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
            if (tox_group_get_chat_id(groupnum, groupid_buf) == 0)
            {
                byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
                groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
                return bytes_to_hex(groupid_buffer).toLowerCase();
            }
            else
            {
                return null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static String bytes_to_hex(byte[] in)
    {
        try
        {
            final StringBuilder builder = new StringBuilder();

            for (byte b : in)
            {
                builder.append(String.format("%02x", b));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return "*ERROR*";
    }

    public static String fourbytes_of_long_to_hex(final long in)
    {
        return String.format("%08x", in);
    }

    public static String bytebuffer_to_hexstring(ByteBuffer in, boolean upper_case)
    {
        try
        {
            in.rewind();
            StringBuilder sb = new StringBuilder("");
            while (in.hasRemaining())
            {
                if (upper_case)
                {
                    sb.append(String.format("%02X", in.get()));
                }
                else
                {
                    sb.append(String.format("%02x", in.get()));
                }
            }
            in.rewind();
            return sb.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static class incoming_group_file_meta_data
    {
        long rowid;
        String message_text;
        String path_name;
        String file_name;
    }

    static incoming_group_file_meta_data handle_incoming_group_file(long group_number, long peer_id, byte[] data, long length, long header)
    {
        incoming_group_file_meta_data ret = new incoming_group_file_meta_data();
        ret.message_text = null;
        ret.path_name = null;
        ret.file_name = null;
        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: do not add our own messages, they are already in the DB!
                Log.i(TAG, "group_custom_packet_cb:gn=" + group_number + " peerid=" + peer_id + " ignoring own file");
                return null;
            }

            String group_id = "-1";
            try
            {
                group_id = tox_group_by_groupnum__wrapper(group_number);
            }
            catch (Exception ignored)
            {
            }

            if (group_id.compareTo("-1") == 0)
            {
                return null;
            }


            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            hash_bytes.put(data, 8, 32);

            // TODO: fix me!
            long timestamp_unused = ((byte)data[8+32]<<3) + ((byte)data[8+32+1]<<2) + ((byte)data[8+32+2]<<1) + (byte)data[8+32+3];

            ByteBuffer filename_bytes = ByteBuffer.allocateDirect(TOX_MAX_FILENAME_LENGTH);
            filename_bytes.put(data, 8 + 32 + 4, 255);
            filename_bytes.rewind();
            String filename = "image.jpg";
            try
            {
                byte[] filename_byte_buf = new byte[255];
                Arrays.fill(filename_byte_buf, (byte)0x0);
                filename_bytes.rewind();
                filename_bytes.get(filename_byte_buf);

                int start_index = 0;
                int end_index = 254;
                for(int j=0;j<255;j++)
                {
                    if (filename_byte_buf[j] == 0)
                    {
                        start_index = j+1;
                    }
                    else
                    {
                        break;
                    }
                }

                for(int j=254;j>=0;j--)
                {
                    if (filename_byte_buf[j] == 0)
                    {
                        end_index = j;
                    }
                    else
                    {
                        break;
                    }
                }

                byte[] filename_byte_buf_stripped = Arrays.copyOfRange(filename_byte_buf,start_index,end_index);
                filename = new String(filename_byte_buf_stripped, StandardCharsets.UTF_8);
                //Log.i(TAG,"group_custom_packet_cb:filename str=" + filename);

                //Log.i(TAG, "group_custom_packet_cb:filename:"+filename_bytes.arrayOffset()+" "
                //+filename_bytes.limit()+" "+filename_bytes.array().length);
                //Log.i(TAG, "group_custom_packet_cb:filename hex="
                //           + HelperGeneric.bytesToHex(filename_bytes.array(),filename_bytes.arrayOffset(),filename_bytes.limit()));
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

            long file_size = length - header;
            if (file_size < 1)
            {
                Log.i(TAG, "group_custom_packet_cb: file size less than 1 byte");
                return null;
            }

            String filename_corrected = get_incoming_filetransfer_local_filename(filename, group_id.toLowerCase());

            String tox_peerpk = tox_group_peer_get_public_key(group_number, peer_id).toUpperCase();
            String peername = tox_group_peer_get_name(group_number, peer_id);
            long timestamp = System.currentTimeMillis();

            GroupMessage m = new GroupMessage();
            m.is_new = false;
            m.tox_group_peer_pubkey = tox_peerpk;
            m.direction = TRIFA_MSG_DIRECTION.TRIFA_MSG_DIRECTION_RECVD.value;
            m.TOX_MESSAGE_TYPE = 0;
            m.read = false;
            m.tox_group_peername = peername;
            m.private_message = 0;
            m.group_identifier = group_id.toLowerCase();
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.rcvd_timestamp = timestamp;
            m.sent_timestamp = timestamp;
            m.text = filename_corrected + "\n" + file_size + " bytes";
            m.message_id_tox = "";
            m.was_synced = false;
            m.path_name = VFS_FILE_DIR + "/" + m.group_identifier + "/";
            m.file_name = filename_corrected;
            m.filename_fullpath = m.path_name + m.file_name;
            m.msg_id_hash = bytebuffer_to_hexstring(hash_bytes, true);
            m.filesize = file_size;

            File f1 = new File(m.path_name + "/" + m.file_name);
            File f2 = new File(f1.getParent());
            f2.mkdirs();

            save_group_incoming_file(m.path_name, m.file_name, data, header, file_size);
            long row_id = -1;
            try
            {
                row_id = TrifaToxService.Companion.getOrma().insertIntoGroupMessage(m);
            } catch (Exception e)
            {
            }

            ret.message_text =  m.text;
            ret.path_name = m.path_name;
            ret.file_name = m.file_name;
            ret.rowid = row_id;

            return ret; // return metadata
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    static void delete_group_all_messages(final String group_identifier)
    {
        try
        {
            TrifaToxService.Companion.getOrma().deleteFromGroupMessage().group_identifierEq(group_identifier.toLowerCase()).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "group_conference_all_messages:EE:" + e.getMessage());
        }
    }

    static void delete_group(final String group_identifier)
    {
        try
        {
            TrifaToxService.Companion.getOrma().deleteFromGroupDB().group_identifierEq(group_identifier.toLowerCase()).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "delete_group:EE:" + e.getMessage());
        }
    }

    static int send_group_image(final GroupMessage g)
    {
        // @formatter:off
        /*
           40000 max bytes length for custom lossless NGC packets.
           37000 max bytes length for file and header, to leave some space for offline message syncing.

        | what      | Length in bytes| Contents                                           |
        |------     |--------        |------------------                                  |
        | magic     |       6        |  0x667788113435                                    |
        | version   |       1        |  0x01                                              |
        | pkt id    |       1        |  0x11                                              |
        | msg id    |      32        | *uint8_t  to uniquely identify the message         |
        | create ts |       4        |  uint32_t unixtimestamp in UTC of local wall clock |
        | filename  |     255        |  len TOX_MAX_FILENAME_LENGTH                       |
        |           |                |      data first, then pad with NULL bytes          |
        | data      |[1, 36701]      |  bytes of file data, zero length files not allowed!|


        header size: 299 bytes
        data   size: 1 - 36701 bytes
         */
        // @formatter:on

        final long header = 6 + 1 + 1 + 32 + 4 + 255;
        long data_length = header + g.filesize;

        if ((data_length > TOX_MAX_NGC_FILE_AND_HEADER_SIZE) || (data_length < (header + 1)))
        {
            Log.i(TAG, "send_group_image: data length has wrong size: " + data_length);
            return -1;
        }

        ByteBuffer data_buf = ByteBuffer.allocateDirect((int)data_length);

        data_buf.rewind();
        //
        data_buf.put((byte)0x66);
        data_buf.put((byte)0x77);
        data_buf.put((byte)0x88);
        data_buf.put((byte)0x11);
        data_buf.put((byte)0x34);
        data_buf.put((byte)0x35);
        //
        data_buf.put((byte)0x01);
        //
        data_buf.put((byte)0x11);
        //
        try
        {
            data_buf.put(hex_to_bytes(g.msg_id_hash), 0, 32);
        }
        catch(Exception e)
        {
            for(int jj=0;jj<32;jj++)
            {
                data_buf.put((byte)0x0);
            }
        }
        //
        // TODO: write actual timestamp into buffer
        data_buf.put((byte)0x0);
        data_buf.put((byte)0x0);
        data_buf.put((byte)0x0);
        data_buf.put((byte)0x0);
        //
        byte[] fn = "image.jpg".getBytes(StandardCharsets.UTF_8);
        try
        {
            if (g.file_name.getBytes(StandardCharsets.UTF_8).length <= 255)
            {
                fn = g.file_name.getBytes(StandardCharsets.UTF_8);
            }
        }
        catch(Exception ignored)
        {
        }
        data_buf.put(fn);
        for (int k=0;k<(255 - fn.length);k++)
        {
            // fill with null bytes up to 255 for the filename
            data_buf.put((byte) 0x0);
        }
        // -- now fill the data from file --
        java.io.File img_file = new java.io.File(g.filename_fullpath);

        long length_sum = 0;
        java.io.FileInputStream is = null;
        try
        {
            is = new java.io.FileInputStream(img_file);
            byte[] buffer = new byte[1024];
            int length;

            while ((length = is.read(buffer)) > 0)
            {
                data_buf.put(buffer, 0, length);
                length_sum = length_sum + length;
                Log.i(TAG,"put " + length + " bytes into buffer");
            }
        }
        catch(Exception e)
        {
        }
        finally
        {
            try
            {
                is.close();
            }
            catch(Exception e2)
            {
            }
        }
        Log.i(TAG,"put " + length_sum + " bytes TOTAL into buffer, and should match " + g.filesize);
        // -- now fill the data from file --

        byte[] data = new byte[(int)data_length];
        data_buf.rewind();
        data_buf.get(data);
        int res = tox_group_send_custom_packet(tox_group_by_groupid__wrapper(g.group_identifier),
                1,
                data,
                (int)data_length);
        return res;
    }

    /*
    this is a bit costly, asking for pubkeys of all group peers
    */
    static long get_group_peernum_from_peer_pubkey(final String group_identifier, final String peer_pubkey)
    {
        try
        {
            long group_num = tox_group_by_groupid__wrapper(group_identifier);
            long num_peers = MainActivity.tox_group_peer_count(group_num);

            if (num_peers > 0)
            {
                long[] peers = tox_group_get_peerlist(group_num);
                if (peers != null)
                {
                    long i = 0;
                    for (i = 0; i < num_peers; i++)
                    {
                        try
                        {
                            String pubkey_try = tox_group_peer_get_public_key(group_num, peers[(int) i]);
                            if (pubkey_try != null)
                            {
                                if (pubkey_try.equals(peer_pubkey))
                                {
                                    // we found the peer number
                                    return peers[(int) i];
                                }
                            }
                        }
                        catch (Exception e)
                        {
                        }
                    }
                }
            }
            return -2;
        }
        catch (Exception e)
        {
            return -2;
        }
    }

    public static String bytesToHexJava(byte[] bytes, int start, int len)
    {
        char[] hexChars = new char[(len) * 2];
        // System.out.println("blen=" + (len));

        for (int j = start; j < (start + len); j++)
        {
            int v = bytes[j] & 0xFF;
            hexChars[(j - start) * 2] = getHexArray()[v >>> 4];
            hexChars[(j - start) * 2 + 1] = getHexArray()[v & 0x0F];
        }

        return new String(hexChars);
    }

    static void handle_incoming_sync_group_message(final long group_number, final long peer_id, final byte[] data, final long length)
    {
        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: do not add our own messages, they are already in the DB!
                Log.i(TAG, "handle_incoming_sync_group_message:gn=" + group_number + " peerid=" + peer_id + " ignoring self");
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String syncer_pubkey = tox_group_peer_get_public_key(group_number, peer_id);

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_GROUP_PEER_PUBLIC_KEY_SIZE);
            hash_bytes.put(data, 8 + 4, 32);
            ByteBufferCompat hash_bytes_compat = new ByteBufferCompat(hash_bytes);
            final String original_sender_peerpubkey = bytesToHexJava
                    (hash_bytes_compat.array(),hash_bytes_compat.arrayOffset(),hash_bytes_compat.limit()).toUpperCase();
            // Log.i(TAG, "handle_incoming_sync_group_message:peerpubkey hex=" + original_sender_peerpubkey);

            if (tox_group_self_get_public_key(group_number).toUpperCase().equalsIgnoreCase(original_sender_peerpubkey))
            {
                // HINT: do not add our own messages, they are already in the DB!
                // Log.i(TAG, "handle_incoming_sync_group_message:gn=" + group_number + " peerid=" + peer_id + " ignoring myself as original sender");
                return;
            }
            //
            //
            // HINT: putting 4 bytes unsigned int in big endian format into a java "long" is more complex than i thought
            ByteBuffer timestamp_byte_buffer = ByteBuffer.allocateDirect(8);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put(data, 8+4+32, 4);
            timestamp_byte_buffer.order(java.nio.ByteOrder.BIG_ENDIAN);
            timestamp_byte_buffer.rewind();
            long timestamp = timestamp_byte_buffer.getLong();
            //Log.i(TAG,"handle_incoming_sync_group_message:got_ts_bytes:" +
            //          HelperGeneric.bytesToHex(data, 8+4+32, 4));
            timestamp_byte_buffer.rewind();
            //Log.i(TAG,"handle_incoming_sync_group_message:got_ts_bytes:bytebuffer:" +
            //          HelperGeneric.bytesToHex(timestamp_byte_buffer.array(),
            //                                   timestamp_byte_buffer.arrayOffset(),
            //                                  timestamp_byte_buffer.limit()));

            // Log.i(TAG, "handle_incoming_sync_group_message:timestamp=" + timestamp);

            if (timestamp > ((System.currentTimeMillis() / 1000) + (60 * 5)))
            {
                long delta_t = timestamp - (System.currentTimeMillis() / 1000);
                // Log.i(TAG, "handle_incoming_sync_group_message:delta t=" + delta_t + " do NOT sync messages from the future");
                return;
            }
            else if (timestamp < ((System.currentTimeMillis() / 1000) - (60 * 200)))
            {
                long delta_t = (System.currentTimeMillis() / 1000) - timestamp;
                // Log.i(TAG, "handle_incoming_sync_group_message:delta t=" + (-delta_t) + " do NOT sync messages that are too old");
                return;
            }

            //
            //
            //
            ByteBuffer hash_msg_id_bytes = ByteBuffer.allocateDirect(4);
            hash_msg_id_bytes.put(data, 8, 4);
            ByteBufferCompat hash_msg_id_bytes_compat = new ByteBufferCompat(hash_msg_id_bytes);
            final String message_id_tox = bytesToHexJava(hash_msg_id_bytes_compat.array(),hash_msg_id_bytes_compat.arrayOffset(),hash_msg_id_bytes_compat.limit()).toLowerCase();
            // Log.i(TAG, "handle_incoming_sync_group_message:message_id_tox hex=" + message_id_tox);
            //
            //
            ByteBuffer name_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
            name_buffer.put(data, 8 + 4 + 32 + 4, TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
            name_buffer.rewind();
            String peer_name = "peer";
            try
            {
                byte[] name_byte_buf = new byte[TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES];
                Arrays.fill(name_byte_buf, (byte)0x0);
                name_buffer.rewind();
                name_buffer.get(name_byte_buf);

                int start_index = 0;
                int end_index = TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES - 1;
                for(int j=0;j<TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES;j++)
                {
                    if (name_byte_buf[j] == 0)
                    {
                        start_index = j+1;
                    }
                    else
                    {
                        break;
                    }
                }

                for(int j=(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES-1);j>=0;j--)
                {
                    if (name_byte_buf[j] == 0)
                    {
                        end_index = j;
                    }
                    else
                    {
                        break;
                    }
                }

                byte[] peername_byte_buf_stripped = Arrays.copyOfRange(name_byte_buf, start_index,end_index);
                peer_name = new String(peername_byte_buf_stripped, StandardCharsets.UTF_8);
                // Log.i(TAG,"handle_incoming_sync_group_message:peer_name str=" + peer_name);
                //
                final int header = 6+1+1+4+32+4+25; // 73 bytes
                long text_size = length - header;
                if ((text_size < 1) || (text_size > 37000))
                {
                    Log.i(TAG, "handle_incoming_sync_group_message: text size less than 1 byte or larger than 37000 bytes");
                    return;
                }

                byte[] text_byte_buf = Arrays.copyOfRange(data, header, (int)length);
                String message_str = new String(text_byte_buf, StandardCharsets.UTF_8);
                // Log.i(TAG,"handle_incoming_sync_group_message:message str=" + message_str);

                long sender_peer_num = HelperGroup.get_group_peernum_from_peer_pubkey(group_identifier,
                        original_sender_peerpubkey);

                GroupMessage gm = get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey(
                        group_identifier, original_sender_peerpubkey, (timestamp * 1000),
                        message_id_tox, MESSAGE_GROUP_HISTORY_SYNC_DOUBLE_INTERVAL_SECS, message_str);

                if (gm != null)
                {
                    // Log.i(TAG,"handle_incoming_sync_group_message:potential double message:" + message_str);
                    return;
                }

                long peernum = tox_group_peer_by_public_key(group_number, original_sender_peerpubkey);
                final String peer_name_saved = tox_group_peer_get_name(group_number, peernum);
                if (peer_name_saved != null)
                {
                    // HINT: use saved name instead of name from sync message
                    peer_name = peer_name_saved;
                }

                group_message_add_from_sync(group_identifier, syncer_pubkey, sender_peer_num, original_sender_peerpubkey,
                        TRIFA_MSG_TYPE_TEXT.value, message_str, message_str.length(),
                        (timestamp * 1000), message_id_tox,
                        TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value,
                        peer_name);
            }
            catch(Exception e)
            {
                e.printStackTrace();
                Log.i(TAG,"handle_incoming_sync_group_message:EE002:" + e.getMessage());
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "handle_incoming_sync_group_message:EE001:" + e.getMessage());
        }
    }

    static void send_ngch_request(final String group_identifier, final String peer_pubkey)
    {
        try
        {
            long res = tox_group_self_get_peer_id(tox_group_by_groupid__wrapper(group_identifier));
            if (res == get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey))
            {
                // HINT: ignore own packets
                Log.i(TAG, "send_ngch_request:dont send to self");
                return;
            }
        }
        catch(Exception e)
        {
        }

        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // HINT: sleep "5 + random(0 .. 6)" seconds
                    java.util.Random rand = new java.util.Random();
                    int rndi = rand.nextInt(7);
                    int n = 5 + rndi;
                    // Log.i(TAG,"send_ngch_request: sleep for " + n + " seconds");
                    Thread.sleep(1000 * n);
                    //
                    final int data_length = 6 + 1 + 1;
                    ByteBuffer data_buf = ByteBuffer.allocateDirect(data_length);

                    data_buf.rewind();
                    //
                    data_buf.put((byte) 0x66);
                    data_buf.put((byte) 0x77);
                    data_buf.put((byte) 0x88);
                    data_buf.put((byte) 0x11);
                    data_buf.put((byte) 0x34);
                    data_buf.put((byte) 0x35);
                    //
                    data_buf.put((byte) 0x1);
                    //
                    data_buf.put((byte) 0x1);

                    byte[] data = new byte[data_length];
                    data_buf.rewind();
                    data_buf.get(data);
                    int result = tox_group_send_custom_private_packet(
                            tox_group_by_groupid__wrapper(group_identifier),
                            get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey),
                            1,
                            data,
                            data_length);
                    Log.i(TAG,"send_ngch_request: sending request:result=" + result);
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        t.start();
    }

    @Nullable
    static GroupMessage get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey(
            String group_identifier, String sender_pubkey, long sent_timestamp, String message_id_tox,
            long time_delta_ms, final String message_text)
    {
        try
        {
            if ((message_id_tox == null) || (message_id_tox.length() < 8))
            {
                return null;
            }

            GroupMessage gm = TrifaToxService.Companion.getOrma().selectFromGroupMessage().
                    group_identifierEq(group_identifier.toLowerCase()).
                    tox_group_peer_pubkeyEq(sender_pubkey.toUpperCase()).
                    message_id_toxEq(message_id_tox.toLowerCase()).
                    sent_timestampGt(sent_timestamp - (time_delta_ms * 1000)).
                    textEq(message_text).
                    limit(1).
                    toList().
                    get(0);

            return gm;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static void group_message_add_from_sync(final String group_identifier, final String syncer_pubkey,
                                            long peer_number2, String peer_pubkey, int a_TOX_MESSAGE_TYPE,
                                            String message, long length, long sent_timestamp_in_ms,
                                            String message_id, int sync_type, final String peer_name)
    {
        // Log.i(TAG,
        //       "group_message_add_from_sync:cf_num=" + group_identifier + " pnum=" + peer_number2 + " msg=" + message);

        long group_num_ = tox_group_by_groupid__wrapper(group_identifier);
        int res = -1;
        if (peer_number2 == -1)
        {
            res = -1;
        }
        else
        {
            final long my_peer_num = tox_group_self_get_peer_id(group_num_);
            if (my_peer_num == peer_number2)
            {
                res = 1;
            }
            else
            {
                res = 0;
            }
        }

        if (res == 1)
        {
            // HINT: do not add our own messages, they are already in the DB!
            // Log.i(TAG, "conference_message_add_from_sync:own peer");
            return;
        }

        GroupMessage m = new GroupMessage();
        m.is_new = false;
        m.tox_group_peer_pubkey = peer_pubkey;
        m.direction = TRIFA_MSG_DIRECTION.TRIFA_MSG_DIRECTION_RECVD.value;
        m.TOX_MESSAGE_TYPE = 0;
        m.read = false;
        m.tox_group_peername = peer_name;
        m.group_identifier = group_identifier;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.sent_timestamp = sent_timestamp_in_ms;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.text = message;
        m.message_id_tox = message_id;
        m.was_synced = true;

        if (m.tox_group_peername == null)
        {
            try
            {
                m.tox_group_peername = tox_group_peer_get_name(group_num_, peer_number2);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        Companion.incoming_synced_group_text_msg(m);
    }
}
