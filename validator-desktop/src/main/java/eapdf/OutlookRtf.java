package eapdf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Bounded MS-OXRTFCP decoding for original inline-resource references. */
final class OutlookRtf {
  static byte[] decode(byte[] input) throws IOException {
    if (input.length < 16) throw new IOException("Truncated Outlook RTF header");
    var header = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
    long packed = Integer.toUnsignedLong(header.getInt()) + 4;
    int size = header.getInt(), kind = header.getInt(), expectedCrc = header.getInt();
    if (packed != input.length || size < 0 || size > 16 * 1024 * 1024) throw new IOException("Outlook RTF size is invalid or exceeds the inspection limit");
    if (kind == 0x414c454d) {
      if (input.length - 16 < size) throw new IOException("Truncated uncompressed Outlook RTF");
      return java.util.Arrays.copyOfRange(input, 16, 16 + size);
    }
    if (kind != 0x75465a4c) throw new IOException("Unknown Outlook RTF compression");
    int crc = 0;
    for (int i = 16; i < input.length; i++) {
      crc ^= Byte.toUnsignedInt(input[i]);
      for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320 : 0);
    }
    if (crc != expectedCrc) throw new IOException("Outlook RTF checksum mismatch");
    byte[] preload = ("{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss "
        + "\\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier"
        + "{\\colortbl\\red0\\green0\\blue0\n\r\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx").getBytes(StandardCharsets.US_ASCII);
    byte[] dictionary = new byte[4096], output = new byte[size];
    System.arraycopy(preload, 0, dictionary, 0, preload.length);
    int write = preload.length, position = 16, count = 0;
    while (count < size && position < input.length) {
      int flags = Byte.toUnsignedInt(input[position++]);
      for (int bit = 0; bit < 8 && count < size; bit++) {
        if (position >= input.length) throw new IOException("Truncated compressed Outlook RTF");
        if ((flags & (1 << bit)) == 0) {
          byte value = input[position++]; output[count++] = value; dictionary[write] = value; write = (write + 1) & 4095;
        } else {
          if (position + 1 >= input.length) throw new IOException("Truncated Outlook RTF dictionary reference");
          int token = (Byte.toUnsignedInt(input[position++]) << 8) | Byte.toUnsignedInt(input[position++]);
          int offset = token >>> 4, length = (token & 15) + 2;
          if (offset == write) throw new IOException("Outlook RTF ended before its declared size");
          for (int i = 0; i < length && count < size; i++) {
            byte value = dictionary[(offset + i) & 4095]; output[count++] = value; dictionary[write] = value; write = (write + 1) & 4095;
          }
        }
      }
    }
    if (count != size) throw new IOException("Truncated Outlook RTF body");
    return output;
  }
}
