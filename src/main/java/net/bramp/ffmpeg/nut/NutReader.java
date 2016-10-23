package net.bramp.ffmpeg.nut;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.bramp.ffmpeg.nut.Packet.Startcode;

/**
 * Demuxer for the FFmpeg Nut file format.
 *
 * Lots of things not implemented, startcode searching, crc checks, etc
 *
 * @see <a
 *      href="https://www.ffmpeg.org/~michael/nut.txt">https://www.ffmpeg.org/~michael/nut.txt</a>
 * @see <a
 *      href="https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/nutdec.c">https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/nutdec.c</a>
 */
public class NutReader {

  // "nut/multimedia container\0"
  static final byte[] HEADER = {0x6e, 0x75, 0x74, 0x2f, 0x6d, 0x75, 0x6c, 0x74, 0x69, 0x6d, 0x65,
      0x64, 0x69, 0x61, 0x20, 0x63, 0x6f, 0x6e, 0x74, 0x61, 0x69, 0x6e, 0x65, 0x72, 0x00};

  public MainHeaderPacket header;
  public final List<Stream> streams = new ArrayList<>();

  final NutDataInputStream in;
  final NutReaderListener listener;

  public NutReader(InputStream in, NutReaderListener listener) {
    this.in = new NutDataInputStream(in);
    this.listener = checkNotNull(listener);
  }

  public static boolean isKnownStartcode(long startcode) {
    return Startcode.of(startcode) != null;
  }

  /**
   * Read the magic at the beginning of the file.
   * 
   * @throws IOException
   */
  protected void readFileId() throws IOException {
    byte[] b = new byte[HEADER.length];
    in.readFully(b);

    if (!Arrays.equals(b, HEADER)) {
      throw new IOException("file_id_string does not match. got: " + new String(b));
    }
  }

  /**
   * Read headers we don't know how to parse yet, returning the next startcode.
   * 
   * @return
   * @throws IOException
   */
  protected long readReservedHeaders() throws IOException {
    long startcode = in.readStartCode();

    while (Startcode.isPossibleStartcode(startcode) && isKnownStartcode(startcode)) {
      new Packet().read(in, startcode); // Discard unknown packet
      startcode = in.readStartCode();
    }
    return startcode;
  }

  /**
   * Demux the inputstream
   * 
   * @throws IOException
   */
  public void read() throws IOException {
    readFileId();
    in.resetCRC();

    long startcode = in.readStartCode();

    while (true) {
      // Start parsing main and stream information
      header = new MainHeaderPacket();

      if (Startcode.MAIN.equals(startcode)) {
        header.read(in, startcode);
      } else {
        throw new IOException(String.format("expected main header found: %X", startcode));
      }

      startcode = readReservedHeaders();

      streams.clear();
      for (int i = 0; i < header.streamCount; i++) {
        if (!Startcode.STREAM.equals(startcode)) {
          throw new IOException(String.format("expected stream header found: %X", startcode));
        }

        StreamHeaderPacket streamHeader = new StreamHeaderPacket();
        streamHeader.read(in, startcode);

        Stream stream = new Stream(header, streamHeader);
        streams.add(stream);
        listener.stream(stream);

        startcode = readReservedHeaders();
      }

      while (Startcode.INFO.equals(startcode)) {
        new Packet().read(in, startcode); // Discard for the moment
        startcode = readReservedHeaders();
      }

      if (Startcode.INDEX.equals(startcode)) {
        new Packet().read(in, startcode); // Discard for the moment
        startcode = in.readStartCode();
      }

      // Now main frame parsing loop
      while (!Startcode.MAIN.equals(startcode)) {

        if (Startcode.SYNCPOINT.equals(startcode)) {
          new Packet().read(in, startcode); // Discard for the moment
          startcode = in.readStartCode();
        }

        if (Startcode.isPossibleStartcode(startcode)) {
          throw new IOException("expected framecode, found " + Startcode.toString(startcode));
        }

        Frame f = new Frame();
        f.read(this, in, (int) startcode);

        listener.frame(f);

        try {
          startcode = readReservedHeaders();
        } catch (java.io.EOFException e) {
          return;
        }
      }
    }
  }
}
