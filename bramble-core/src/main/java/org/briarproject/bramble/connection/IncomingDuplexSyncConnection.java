package org.briarproject.bramble.connection;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.HandshakeManager;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.sync.PriorityHandler;
import org.briarproject.bramble.api.sync.SyncSession;
import org.briarproject.bramble.api.sync.SyncSessionFactory;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logException;

@NotNullByDefault
class IncomingDuplexSyncConnection extends DuplexSyncConnection
		implements Runnable {
	private final HandshakeManager handshakeManager;

	IncomingDuplexSyncConnection(KeyManager keyManager,
			ConnectionRegistry connectionRegistry,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SyncSessionFactory syncSessionFactory,
			TransportPropertyManager transportPropertyManager,
			Executor ioExecutor, TransportId transportId,
			DuplexTransportConnection connection,
			HandshakeManager handshakeManager) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager, ioExecutor, transportId, connection);
		this.handshakeManager = handshakeManager;
	}

	@Override
	public void run() {
		// Read and recognise the tag
		StreamContext ctx = recogniseTag(reader, transportId);
		if (ctx == null) {
			LOG.info("Unrecognised tag");
			onReadError(false);
			return;
		}
		ContactId contactId = ctx.getContactId();
		if (contactId == null) {
			LOG.warning("Expected contact tag, got rendezvous tag");
			onReadError(true);
			return;
		}
		if (ctx.isHandshakeMode()) {

			StreamContext ctxIn = ctx;
			PendingContactId inPendingContactId = ctxIn.getPendingContactId();
			if (inPendingContactId == null) {
				LOG.warning("Expected rendezvous tag, got contact tag");
				onReadError(true);
				return;
			}
			// Allocate the outgoing stream context
			StreamContext ctxOut =
					allocateStreamContext(contactId, transportId);
			if (ctxOut == null) {
				LOG.warning("Could not allocate stream context");
				onReadError(true);
				return;
			}
			try {
				InputStream in = streamReaderFactory.createStreamReader(
						reader.getInputStream(), ctxIn);
				// Flush the output stream to send the outgoing stream header
				StreamWriter out = streamWriterFactory.createStreamWriter(
						writer.getOutputStream(), ctxOut);
				out.getOutputStream().flush();
				HandshakeManager.HandshakeResult result =
						handshakeManager.handshake(contactId, in, out);

				return;
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				onReadError(true);
			}
			// TODO: Support handshake mode for contacts
//			LOG.warning("Received handshake tag, expected rotation mode");
//			onReadError(true);
//			return;
		}
		connectionRegistry.registerIncomingConnection(contactId, transportId,
				this);
		// Start the outgoing session on another thread
		ioExecutor.execute(() -> runOutgoingSession(contactId));
		try {
			// Store any transport properties discovered from the connection
			transportPropertyManager.addRemotePropertiesFromConnection(
					contactId, transportId, remote);
			// Update the connection registry when we receive our priority
			PriorityHandler handler = p -> connectionRegistry.setPriority(
					contactId, transportId, this, p);
			// Create and run the incoming session
			createIncomingSession(ctx, reader, handler).run();
			reader.dispose(false, true);
			interruptOutgoingSession();
			connectionRegistry.unregisterConnection(contactId, transportId,
					this, true, false);
		} catch (DbException | IOException e) {
			logException(LOG, WARNING, e);
			onReadError(true);
			connectionRegistry.unregisterConnection(contactId, transportId,
					this, true, true);
		}
	}

	private void runOutgoingSession(ContactId contactId) {
		// Allocate a stream context
		StreamContext ctx = allocateStreamContext(contactId, transportId);
		if (ctx == null) {
			LOG.warning("Could not allocate stream context");
			onWriteError();
			return;
		}
		try {
			// Create and run the outgoing session
			SyncSession out = createDuplexOutgoingSession(ctx, writer, null);
			setOutgoingSession(out);
			out.run();
			writer.dispose(false);
		} catch (IOException e) {
			logException(LOG, WARNING, e);
			onWriteError();
		}
	}
}

