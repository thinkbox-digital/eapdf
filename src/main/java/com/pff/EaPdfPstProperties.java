package com.pff;

/** Access to native fields without public getters in java-libpst 0.9.3. */
public final class EaPdfPstProperties {
  private EaPdfPstProperties() {}

  public static String internetReferences(PSTMessage message) {
    // Use the library's own Unicode/code-page and external-value decoding.
    return message.getStringItem(0x1039);
  }

  public static boolean hasAttachmentPayload(PSTAttachment attachment) {
    return attachment.items.containsKey(0x3701);
  }

  public static void checkRtfHeader(byte[] header, long encodedSize, long maximumDecoded) throws java.io.IOException {
    if (header.length < 16) throw new java.io.IOException("Truncated compressed RTF header");
    long decoded = java.nio.ByteBuffer.wrap(header, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
    int signature = java.nio.ByteBuffer.wrap(header, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
    if (decoded > maximumDecoded || (signature == 0x414c454d && encodedSize - 16 > maximumDecoded))
      throw new java.io.IOException("RTF expansion exceeds the body processing limit");
  }

  public static String boundedRtf(PSTMessage message, long maximumEncoded, long maximumDecoded) throws java.io.IOException, PSTException {
    var item = message.items.get(0x1009);
    if (item == null) return "";
    byte[] data = item.data;
    if (data.length == 0) {
      var descriptor = message.localDescriptorItems.get(item.entryValueReference);
      if (descriptor == null) return "";
      if (descriptor.getDataSize() < 0 || descriptor.getDataSize() > maximumEncoded)
        throw new java.io.IOException("Compressed RTF exceeds the message processing limit");
      data = descriptor.getData();
    }
    if (data.length > maximumEncoded) throw new java.io.IOException("Compressed RTF exceeds the message processing limit");
    checkRtfHeader(data, data.length, maximumDecoded);
    return LZFu.decode(data);
  }
}
