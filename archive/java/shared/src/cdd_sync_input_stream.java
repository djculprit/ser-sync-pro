import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

/**
 * Binary input stream for reading Serato crate and database files.
 */
public class cdd_sync_input_stream extends DataInputStream {

    public cdd_sync_input_stream(InputStream in) {
        super(in);
    }

    /**
     * Reads variable number of bytes as a long value.
     */
    public long readLongValue(int bytes) throws cdd_sync_exception {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value <<= 8;
            try {
                value += readUnsignedByte();
            } catch (IOException e) {
                throw new cdd_sync_exception(e);
            }
        }
        return value;
    }

    /**
     * Reads 4 bytes as an integer value.
     */
    public int readIntegerValue() throws cdd_sync_exception {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value <<= 8;
            try {
                value += readUnsignedByte();
            } catch (IOException e) {
                throw new cdd_sync_exception(e);
            }
        }
        return value;
    }

    /**
     * Reads and validates an exact string.
     * 
     * @return true if EOF reached
     */
    public boolean skipExactString(String expected) throws cdd_sync_exception {
        byte[] data = new byte[expected.length()];
        try {
            int read = read(data);
            if (read < 0) {
                return true;
            }
            if (read != data.length) {
                throw new cdd_sync_exception("Expected '" + expected + "', but read only " + read + " bytes");
            }
        } catch (IOException e) {
            throw new cdd_sync_exception(e);
        }
        String dataAsString = new String(data);
        if (!expected.equals(dataAsString)) {
            throw new cdd_sync_exception("Expected '" + expected + "' but found '" + dataAsString + "'");
        }
        return false;
    }

    /**
     * Reads and validates an exact UTF-16 string.
     * 
     * @return true if EOF reached
     */
    public boolean skipExactStringUTF16(String expected) throws cdd_sync_exception {
        byte[] data = new byte[expected.length() << 1];
        try {
            int read = read(data);
            if (read < 0) {
                return true;
            }
            if (read != data.length) {
                throw new cdd_sync_exception("Expected '" + expected + "', but read only " + read + " bytes");
            }
        } catch (IOException e) {
            throw new cdd_sync_exception(e);
        }
        String dataAsString;
        try {
            dataAsString = new String(data, "UTF-16");
        } catch (UnsupportedEncodingException e) {
            throw new cdd_sync_exception(e);
        }
        if (!expected.equals(dataAsString)) {
            throw new cdd_sync_exception("Expected '" + expected + "' but found '" + dataAsString + "'");
        }
        return false;
    }

    /**
     * Reads and validates an exact byte.
     * 
     * @return true if EOF reached
     */
    public boolean skipExactByte(byte expected) throws cdd_sync_exception {
        byte[] data = new byte[1];
        try {
            int read = read(data);
            if (read < 0) {
                return true;
            }
            if (read != data.length) {
                throw new cdd_sync_exception(
                        "Expected a single byte '" + expected + "', but was unable to read anything");
            }
        } catch (IOException e) {
            throw new cdd_sync_exception(e);
        }
        if (data[0] != expected) {
            throw new cdd_sync_exception("Expected a single byte " + expected + " but found '" + data[0] + "'");
        }
        return false;
    }

    /**
     * Reads a UTF-16 string of specified byte length.
     */
    public String readStringUTF16(int length) throws cdd_sync_exception {
        byte[] data = new byte[length];
        try {
            int read = read(data);
            if (read != length) {
                throw new cdd_sync_exception("Expected to read " + length + " bytes, but read only " + read);
            }
        } catch (IOException e) {
            throw new cdd_sync_exception(e);
        }
        try {
            return new String(data, "UTF-16");
        } catch (UnsupportedEncodingException e) {
            throw new cdd_sync_exception(e);
        }
    }

    /**
     * Reads a string of specified byte length.
     */
    public String readString(int length) throws cdd_sync_exception {
        byte[] data = new byte[length];
        try {
            int read = read(data);
            if (read != length) {
                throw new cdd_sync_exception("Expected to read " + length + " bytes, but read only " + read);
            }
        } catch (IOException e) {
            throw new cdd_sync_exception(e);
        }
        return new String(data);
    }
}
