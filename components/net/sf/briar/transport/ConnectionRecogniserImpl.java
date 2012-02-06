package net.sf.briar.transport;

import static net.sf.briar.api.transport.TransportConstants.TAG_LENGTH;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;

import net.sf.briar.api.Bytes;
import net.sf.briar.api.ContactId;
import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.crypto.ErasableKey;
import net.sf.briar.api.db.DatabaseComponent;
import net.sf.briar.api.db.DbException;
import net.sf.briar.api.db.NoSuchContactException;
import net.sf.briar.api.db.event.ContactRemovedEvent;
import net.sf.briar.api.db.event.DatabaseEvent;
import net.sf.briar.api.db.event.DatabaseListener;
import net.sf.briar.api.db.event.RemoteTransportsUpdatedEvent;
import net.sf.briar.api.db.event.TransportAddedEvent;
import net.sf.briar.api.protocol.Transport;
import net.sf.briar.api.protocol.TransportId;
import net.sf.briar.api.protocol.TransportIndex;
import net.sf.briar.api.transport.ConnectionContext;
import net.sf.briar.api.transport.ConnectionRecogniser;
import net.sf.briar.api.transport.ConnectionWindow;
import net.sf.briar.api.transport.IncomingConnectionExecutor;
import net.sf.briar.util.ByteUtils;

import com.google.inject.Inject;

class ConnectionRecogniserImpl implements ConnectionRecogniser,
DatabaseListener {

	private static final Logger LOG =
		Logger.getLogger(ConnectionRecogniserImpl.class.getName());

	private final Executor connExecutor;
	private final DatabaseComponent db;
	private final CryptoComponent crypto;
	private final Cipher tagCipher; // Locking: this
	private final Set<TransportId> localTransportIds; // Locking: this
	private final Map<Bytes, Context> expected; // Locking: this

	private boolean initialised = false; // Locking: this

	@Inject
	ConnectionRecogniserImpl(@IncomingConnectionExecutor Executor connExecutor,
			DatabaseComponent db, CryptoComponent crypto) {
		this.connExecutor = connExecutor;
		this.db = db;
		this.crypto = crypto;
		tagCipher = crypto.getTagCipher();
		localTransportIds = new HashSet<TransportId>();
		expected = new HashMap<Bytes, Context>();
	}

	// Package access for testing
	synchronized boolean isInitialised() {
		return initialised;
	}

	// Locking: this
	private void initialise() throws DbException {
		assert !initialised;
		db.addListener(this);
		Map<Bytes, Context> ivs = new HashMap<Bytes, Context>();
		Collection<TransportId> transports = new ArrayList<TransportId>();
		for(Transport t : db.getLocalTransports()) transports.add(t.getId());
		for(ContactId c : db.getContacts()) {
			try {
				for(TransportId t : transports) {
					TransportIndex i = db.getRemoteIndex(c, t);
					if(i == null) continue; // Contact doesn't support transport
					ConnectionWindow w = db.getConnectionWindow(c, i);
					for(Entry<Long, byte[]> e : w.getUnseen().entrySet()) {
						Context ctx = new Context(c, t, i, e.getKey());
						ivs.put(calculateTag(ctx, e.getValue()), ctx);
					}
					w.erase();
				}
			} catch(NoSuchContactException e) {
				// The contact was removed - clean up in removeContact()
				continue;
			}
		}
		localTransportIds.addAll(transports);
		expected.putAll(ivs);
		initialised = true;
	}

	// Locking: this
	private Bytes calculateTag(Context ctx, byte[] secret) {
		ErasableKey tagKey = crypto.deriveTagKey(secret, true);
		byte[] tag = new byte[TAG_LENGTH];
		TagEncoder.encodeTag(tag, tagCipher, tagKey);
		tagKey.erase();
		return new Bytes(tag);
	}

	public ConnectionContext acceptConnection(TransportId t, byte[] tag)
	throws DbException {
		if(tag.length != TAG_LENGTH)
			throw new IllegalArgumentException();
		synchronized(this) {
			if(!initialised) initialise();
			Bytes b = new Bytes(tag);
			Context ctx = expected.get(b);
			if(ctx == null || !ctx.transportId.equals(t)) return null;
			// The IV was expected
			expected.remove(b);
			ContactId c = ctx.contactId;
			TransportIndex i = ctx.transportIndex;
			long connection = ctx.connection;
			ConnectionWindow w = null;
			byte[] secret = null;
			// Get the secret and update the connection window
			try {
				w = db.getConnectionWindow(c, i);
				secret = w.setSeen(connection);
				db.setConnectionWindow(c, i, w);
			} catch(NoSuchContactException e) {
				// The contact was removed - reject the connection
				if(w != null) w.erase();
				if(secret != null) ByteUtils.erase(secret);
				return null;
			}
			// Update the connection window's expected IVs
			Iterator<Context> it = expected.values().iterator();
			while(it.hasNext()) {
				Context ctx1 = it.next();
				if(ctx1.contactId.equals(c) && ctx1.transportIndex.equals(i))
					it.remove();
			}
			for(Entry<Long, byte[]> e : w.getUnseen().entrySet()) {
				Context ctx1 = new Context(c, t, i, e.getKey());
				expected.put(calculateTag(ctx1, e.getValue()), ctx1);
			}
			w.erase();
			return new ConnectionContextImpl(c, i, connection, secret);
		}
	}

	public void eventOccurred(DatabaseEvent e) {
		if(e instanceof ContactRemovedEvent) {
			// Remove the expected IVs for the ex-contact
			final ContactId c = ((ContactRemovedEvent) e).getContactId();
			connExecutor.execute(new Runnable() {
				public void run() {
					removeContact(c);
				}
			});
		} else if(e instanceof TransportAddedEvent) {
			// Add the expected IVs for the new transport
			final TransportId t = ((TransportAddedEvent) e).getTransportId();
			connExecutor.execute(new Runnable() {
				public void run() {
					addTransport(t);
				}
			});
		} else if(e instanceof RemoteTransportsUpdatedEvent) {
			// Update the expected IVs for the contact
			RemoteTransportsUpdatedEvent r = (RemoteTransportsUpdatedEvent) e;
			final ContactId c = r.getContactId();
			final Collection<Transport> transports = r.getTransports();
			connExecutor.execute(new Runnable() {
				public void run() {
					updateContact(c, transports);
				}
			});
		}
	}

	private synchronized void removeContact(ContactId c) {
		if(!initialised) return;
		Iterator<Context> it = expected.values().iterator();
		while(it.hasNext()) if(it.next().contactId.equals(c)) it.remove();
	}

	private synchronized void addTransport(TransportId t) {
		if(!initialised) return;
		Map<Bytes, Context> ivs = new HashMap<Bytes, Context>();
		try {
			for(ContactId c : db.getContacts()) {
				try {
					TransportIndex i = db.getRemoteIndex(c, t);
					if(i == null) continue; // Contact doesn't support transport
					ConnectionWindow w = db.getConnectionWindow(c, i);
					for(Entry<Long, byte[]> e : w.getUnseen().entrySet()) {
						Context ctx = new Context(c, t, i, e.getKey());
						ivs.put(calculateTag(ctx, e.getValue()), ctx);
					}
					w.erase();
				} catch(NoSuchContactException e) {
					// The contact was removed - clean up in removeContact()
					continue;
				}
			}
		} catch(DbException e) {
			if(LOG.isLoggable(Level.WARNING)) LOG.warning(e.toString());
			return;
		}
		localTransportIds.add(t);
		expected.putAll(ivs);
	}

	private synchronized void updateContact(ContactId c,
			Collection<Transport> transports) {
		if(!initialised) return;
		// The ID <-> index mappings may have changed, so recalculate everything
		Map<Bytes, Context> ivs = new HashMap<Bytes, Context>();
		try {
			for(Transport transport: transports) {
				TransportId t = transport.getId();
				if(!localTransportIds.contains(t)) continue;
				TransportIndex i = transport.getIndex();
				ConnectionWindow w = db.getConnectionWindow(c, i);
				for(Entry<Long, byte[]> e : w.getUnseen().entrySet()) {
					Context ctx = new Context(c, t, i, e.getKey());
					ivs.put(calculateTag(ctx, e.getValue()), ctx);
				}
				w.erase();
			}
		} catch(NoSuchContactException e) {
			// The contact was removed - clean up in removeContact()
			return;
		} catch(DbException e) {
			if(LOG.isLoggable(Level.WARNING)) LOG.warning(e.toString());
			return;
		}
		// Remove the old IVs
		Iterator<Context> it = expected.values().iterator();
		while(it.hasNext()) if(it.next().contactId.equals(c)) it.remove();
		// Store the new IVs
		expected.putAll(ivs);
	}

	private static class Context {

		private final ContactId contactId;
		private final TransportId transportId;
		private final TransportIndex transportIndex;
		private final long connection;

		private Context(ContactId contactId, TransportId transportId,
				TransportIndex transportIndex, long connection) {
			this.contactId = contactId;
			this.transportId = transportId;
			this.transportIndex = transportIndex;
			this.connection = connection;
		}
	}
}
