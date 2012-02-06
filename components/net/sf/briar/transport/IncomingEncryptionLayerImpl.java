package net.sf.briar.transport;

import static net.sf.briar.api.transport.TransportConstants.FRAME_HEADER_LENGTH;
import static net.sf.briar.api.transport.TransportConstants.MAC_LENGTH;
import static net.sf.briar.api.transport.TransportConstants.MAX_FRAME_LENGTH;
import static net.sf.briar.api.transport.TransportConstants.TAG_LENGTH;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;

import net.sf.briar.api.FormatException;
import net.sf.briar.api.crypto.ErasableKey;

class IncomingEncryptionLayerImpl implements FrameReader {

	private final InputStream in;
	private final Cipher tagCipher, frameCipher;
	private final ErasableKey tagKey, frameKey;
	private final int blockSize;
	private final byte[] iv, ciphertext;

	private boolean readTag;
	private long frameNumber;

	IncomingEncryptionLayerImpl(InputStream in, Cipher tagCipher,
			Cipher frameCipher, ErasableKey tagKey, ErasableKey frameKey,
			boolean readTag) {
		this.in = in;
		this.tagCipher = tagCipher;
		this.frameCipher = frameCipher;
		this.tagKey = tagKey;
		this.frameKey = frameKey;
		this.readTag = readTag;
		blockSize = frameCipher.getBlockSize();
		if(blockSize < FRAME_HEADER_LENGTH)
			throw new IllegalArgumentException();
		iv = IvEncoder.encodeIv(0L, blockSize);
		ciphertext = new byte[MAX_FRAME_LENGTH];
		frameNumber = 0L;
	}

	public boolean readFrame(Frame f) throws IOException {
		try {
			// Read the tag if it hasn't already been read
			if(readTag) {
				int offset = 0;
				while(offset < TAG_LENGTH) {
					int read = in.read(ciphertext, offset,
							TAG_LENGTH - offset);
					if(read == -1) {
						if(offset == 0) return false;
						throw new EOFException();
					}
					offset += read;
				}
				if(!TagEncoder.decodeTag(ciphertext, tagCipher, tagKey))
					throw new FormatException();
			}
			// Read the first block of the frame
			int offset = 0;
			while(offset < blockSize) {
				int read = in.read(ciphertext, offset, blockSize - offset);
				if(read == -1) {
					if(offset == 0 && !readTag) return false;
					throw new EOFException();
				}
				offset += read;
			}
			readTag = false;
			// Decrypt the first block of the frame
			byte[] plaintext = f.getBuffer();
			try {
				IvEncoder.updateIv(iv, frameNumber);
				IvParameterSpec ivSpec = new IvParameterSpec(iv);
				frameCipher.init(Cipher.DECRYPT_MODE, frameKey, ivSpec);
				int decrypted = frameCipher.update(ciphertext, 0, blockSize,
						plaintext);
				if(decrypted != blockSize) throw new RuntimeException();
			} catch(GeneralSecurityException badCipher) {
				throw new RuntimeException(badCipher);
			}
			// Parse the frame header
			int payload = HeaderEncoder.getPayloadLength(plaintext);
			int padding = HeaderEncoder.getPaddingLength(plaintext);
			int length = FRAME_HEADER_LENGTH + payload + padding + MAC_LENGTH;
			if(length > MAX_FRAME_LENGTH) throw new FormatException();
			// Read the remainder of the frame
			while(offset < length) {
				int read = in.read(ciphertext, offset, length - offset);
				if(read == -1) throw new EOFException();
				offset += read;
			}
			// Decrypt the remainder of the frame
			try {
				int decrypted = frameCipher.doFinal(ciphertext, blockSize,
						length - blockSize, plaintext, blockSize);
				if(decrypted != length - blockSize)
					throw new RuntimeException();
			} catch(GeneralSecurityException badCipher) {
				throw new RuntimeException(badCipher);
			}
			f.setLength(length);
			frameNumber++;
			return true;
		} catch(IOException e) {
			frameKey.erase();
			tagKey.erase();
			throw e;
		}
	}
}