package org.briarproject.bramble.properties;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_PUBLIC_KEY_LENGTH;
import static org.briarproject.bramble.api.properties.TransportPropertyManager.CLIENT_ID;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_GROUP_DESCRIPTOR_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TransportPropertyManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final Clock clock = context.mock(Clock.class);

	private final Group localGroup = getGroup();
	private final LocalAuthor localAuthor = getLocalAuthor();
	private final BdfDictionary fooPropertiesDict = BdfDictionary.of(
			new BdfEntry("fooKey1", "fooValue1"),
			new BdfEntry("fooKey2", "fooValue2")
	);
	private final BdfDictionary barPropertiesDict = BdfDictionary.of(
			new BdfEntry("barKey1", "barValue1"),
			new BdfEntry("barKey2", "barValue2")
	);
	private final TransportProperties fooProperties, barProperties;

	private int nextContactId = 0;

	public TransportPropertyManagerImplTest() throws Exception {
		fooProperties = new TransportProperties();
		for (String key : fooPropertiesDict.keySet())
			fooProperties.put(key, fooPropertiesDict.getString(key));
		barProperties = new TransportProperties();
		for (String key : barPropertiesDict.keySet())
			barProperties.put(key, barPropertiesDict.getString(key));
	}

	private TransportPropertyManagerImpl createInstance() {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID);
			will(returnValue(localGroup));
		}});
		return new TransportPropertyManagerImpl(db, clientHelper,
				metadataParser, contactGroupFactory, clock);
	}

	@Test
	public void testCreatesGroupsAtStartup() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Contact contact1 = getContact(true);
		final Contact contact2 = getContact(true);
		final List<Contact> contacts = Arrays.asList(contact1, contact2);
		final Group contactGroup1 = getGroup(), contactGroup2 = getGroup();

		context.checking(new Expectations() {{
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));
			// The first contact's group has already been set up
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact1);
			will(returnValue(contactGroup1));
			oneOf(db).containsGroup(txn, contactGroup1.getId());
			will(returnValue(true));
			// The second contact's group hasn't been set up
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact2);
			will(returnValue(contactGroup2));
			oneOf(db).containsGroup(txn, contactGroup2.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, contactGroup2);
			oneOf(db).setGroupVisibility(txn, contact2.getId(),
					contactGroup2.getId(), SHARED);
		}});
		// Copy the latest local properties into the group
		expectGetLocalProperties(txn);
		expectStoreMessage(txn, contactGroup2.getId(), "foo", fooPropertiesDict,
				1, true, true);
		expectStoreMessage(txn, contactGroup2.getId(), "bar", barPropertiesDict,
				1, true, true);

		TransportPropertyManagerImpl t = createInstance();
		t.createLocalState(txn);
	}

	@Test
	public void testCreatesGroupWhenAddingContact() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Contact contact = getContact(true);
		final Group contactGroup = getGroup();

		context.checking(new Expectations() {{
			// Create the group and share it with the contact
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact);
			will(returnValue(contactGroup));
			oneOf(db).containsGroup(txn, contactGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
		}});
		// Copy the latest local properties into the group
		expectGetLocalProperties(txn);
		expectStoreMessage(txn, contactGroup.getId(), "foo", fooPropertiesDict,
				1, true, true);
		expectStoreMessage(txn, contactGroup.getId(), "bar", barPropertiesDict,
				1, true, true);

		TransportPropertyManagerImpl t = createInstance();
		t.addingContact(txn, contact);
	}

	@Test
	public void testRemovesGroupWhenRemovingContact() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Contact contact = getContact(true);
		final Group contactGroup = getGroup();

		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact);
			will(returnValue(contactGroup));
			oneOf(db).removeGroup(txn, contactGroup);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.removingContact(txn, contact);
	}

	@Test
	public void testDoesNotDeleteAnythingWhenFirstUpdateIsDelivered()
			throws Exception {
		final Transaction txn = new Transaction(null, false);
		final GroupId contactGroupId = new GroupId(getRandomId());
		final long timestamp = 123456789;
		final Message message = getMessage(contactGroupId, timestamp);
		final Metadata meta = new Metadata();
		final BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 2),
				new BdfEntry("local", false)
		);
		final Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// A remote update for another transport should be ignored
		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 1),
				new BdfEntry("local", false)
		));
		// A local update for the same transport should be ignored
		MessageId localUpdateId = new MessageId(getRandomId());
		messageMetadata.put(localUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", true)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertFalse(t.incomingMessage(txn, message, meta));
	}

	@Test
	public void testDeletesOlderUpdatesWhenUpdateIsDelivered()
			throws Exception {
		final Transaction txn = new Transaction(null, false);
		final GroupId contactGroupId = new GroupId(getRandomId());
		final long timestamp = 123456789;
		final Message message = getMessage(contactGroupId, timestamp);
		final Metadata meta = new Metadata();
		final BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 4),
				new BdfEntry("local", false)
		);
		final Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// Old remote updates for the same transport should be deleted
		final MessageId fooVersion2 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion2, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 2),
				new BdfEntry("local", false)
		));
		final MessageId fooVersion1 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion1, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", false)
		));
		final MessageId fooVersion3 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion3, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 3),
				new BdfEntry("local", false)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
			// Versions 1-3 should be deleted
			oneOf(db).deleteMessage(txn, fooVersion1);
			oneOf(db).deleteMessageMetadata(txn, fooVersion1);
			oneOf(db).deleteMessage(txn, fooVersion2);
			oneOf(db).deleteMessageMetadata(txn, fooVersion2);
			oneOf(db).deleteMessage(txn, fooVersion3);
			oneOf(db).deleteMessageMetadata(txn, fooVersion3);
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertFalse(t.incomingMessage(txn, message, meta));
	}

	@Test
	public void testDeletesObsoleteUpdateWhenDelivered() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final GroupId contactGroupId = new GroupId(getRandomId());
		final long timestamp = 123456789;
		final Message message = getMessage(contactGroupId, timestamp);
		final Metadata meta = new Metadata();
		final BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 3),
				new BdfEntry("local", false)
		);
		final Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// Old remote updates for the same transport should be deleted
		final MessageId fooVersion2 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion2, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 2),
				new BdfEntry("local", false)
		));
		final MessageId fooVersion1 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion1, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", false)
		));
		// A newer remote update for the same transport should not be deleted
		final MessageId fooVersion4 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion4, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 4),
				new BdfEntry("local", false)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
			// Versions 1 and 2 should be deleted, version 4 should not
			oneOf(db).deleteMessage(txn, fooVersion1);
			oneOf(db).deleteMessageMetadata(txn, fooVersion1);
			oneOf(db).deleteMessage(txn, fooVersion2);
			oneOf(db).deleteMessageMetadata(txn, fooVersion2);
			// The update being delivered (version 3) should be deleted
			oneOf(db).deleteMessage(txn, message.getId());
			oneOf(db).deleteMessageMetadata(txn, message.getId());
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertFalse(t.incomingMessage(txn, message, meta));
	}

	@Test
	public void testStoresRemotePropertiesWithVersion0() throws Exception {
		final Contact contact = getContact(true);
		final Group contactGroup = getGroup();
		final Transaction txn = new Transaction(null, false);
		Map<TransportId, TransportProperties> properties =
				new LinkedHashMap<TransportId, TransportProperties>();
		properties.put(new TransportId("foo"), fooProperties);
		properties.put(new TransportId("bar"), barProperties);

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact);
			will(returnValue(contactGroup));
		}});
		expectStoreMessage(txn, contactGroup.getId(), "foo", fooPropertiesDict,
				0, false, false);
		expectStoreMessage(txn, contactGroup.getId(), "bar", barPropertiesDict,
				0, false, false);

		TransportPropertyManagerImpl t = createInstance();
		t.addRemoteProperties(txn, contact.getId(), properties);
	}

	@Test
	public void testReturnsLatestLocalProperties() throws Exception {
		Transaction txn = new Transaction(null, false);

		expectGetLocalProperties(txn);

		TransportPropertyManagerImpl t = createInstance();
		Map<TransportId, TransportProperties> local = t.getLocalProperties(txn);
		assertEquals(2, local.size());
		assertEquals(fooProperties, local.get(new TransportId("foo")));
		assertEquals(barProperties, local.get(new TransportId("bar")));
	}

	@Test
	public void testReturnsEmptyPropertiesIfNoLocalPropertiesAreFound()
			throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// A local update for another transport should be ignored
		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 1),
				new BdfEntry("local", true)
		));

		context.checking(new Expectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(0, t.getLocalProperties(new TransportId("foo")).size());
	}

	@Test
	public void testReturnsLocalProperties() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// A local update for another transport should be ignored
		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 1),
				new BdfEntry("local", true)
		));
		// A local update for the right transport should be returned
		final MessageId fooUpdateId = new MessageId(getRandomId());
		messageMetadata.put(fooUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", true)
		));
		final BdfList fooUpdate = BdfList.of("foo", 1, fooPropertiesDict);

		context.checking(new Expectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, fooUpdateId);
			will(returnValue(fooUpdate));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(fooProperties,
				t.getLocalProperties(new TransportId("foo")));
	}

	@Test
	public void testReturnsRemotePropertiesOrEmptyProperties()
			throws Exception {
		final Transaction txn = new Transaction(null, false);
		Contact contact1 = getContact(false);
		final Contact contact2 = getContact(true);
		final Contact contact3 = getContact(true);
		final List<Contact> contacts =
				Arrays.asList(contact1, contact2, contact3);
		final Group contactGroup2 = getGroup();
		final Group contactGroup3 = getGroup();
		final Map<MessageId, BdfDictionary> messageMetadata3 =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// A remote update for another transport should be ignored
		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata3.put(barUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 1),
				new BdfEntry("local", false)
		));
		// A local update for the right transport should be ignored
		MessageId localUpdateId = new MessageId(getRandomId());
		messageMetadata3.put(localUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", true)
		));
		// A remote update for the right transport should be returned
		final MessageId fooUpdateId = new MessageId(getRandomId());
		messageMetadata3.put(fooUpdateId, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", false)
		));
		final BdfList fooUpdate = BdfList.of("foo", 1, fooPropertiesDict);

		context.checking(new Expectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));
			// First contact: skipped because not active
			// Second contact: no updates
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact2);
			will(returnValue(contactGroup2));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup2.getId());
			will(returnValue(Collections.emptyMap()));
			// Third contact: returns an update
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact3);
			will(returnValue(contactGroup3));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup3.getId());
			will(returnValue(messageMetadata3));
			oneOf(clientHelper).getMessageAsList(txn, fooUpdateId);
			will(returnValue(fooUpdate));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
		}});

		TransportPropertyManagerImpl t = createInstance();
		Map<ContactId, TransportProperties> properties =
				t.getRemoteProperties(new TransportId("foo"));
		assertEquals(3, properties.size());
		assertEquals(0, properties.get(contact1.getId()).size());
		assertEquals(0, properties.get(contact2.getId()).size());
		assertEquals(fooProperties, properties.get(contact3.getId()));
	}

	@Test
	public void testMergingUnchangedPropertiesDoesNotCreateUpdate()
			throws Exception {
		final Transaction txn = new Transaction(null, false);
		final MessageId updateId = new MessageId(getRandomId());
		final Map<MessageId, BdfDictionary> messageMetadata =
				Collections.singletonMap(updateId, BdfDictionary.of(
						new BdfEntry("transportId", "foo"),
						new BdfEntry("version", 1),
						new BdfEntry("local", true)
				));
		final BdfList update = BdfList.of("foo", 1, fooPropertiesDict);

		context.checking(new Expectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			// Merge the new properties with the existing properties
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, updateId);
			will(returnValue(update));
			// Properties are unchanged so we're done
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), fooProperties);
	}

	@Test
	public void testMergingNewPropertiesCreatesUpdate() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Contact contact = getContact(true);
		final Group contactGroup = getGroup();

		context.checking(new Expectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			// There are no existing properties to merge with
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(Collections.emptyMap()));
			// Store the new properties in the local group, version 1
			expectStoreMessage(txn, localGroup.getId(), "foo",
					fooPropertiesDict, 1, true, false);
			// Store the new properties in each contact's group, version 1
			oneOf(db).getContacts(txn);
			will(returnValue(Collections.singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(Collections.emptyMap()));
			expectStoreMessage(txn, contactGroup.getId(), "foo",
					fooPropertiesDict, 1, true, true);
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), fooProperties);
	}

	@Test
	public void testMergingUpdatedPropertiesCreatesUpdate() throws Exception {
		final Transaction txn = new Transaction(null, false);
		final Contact contact = getContact(true);
		final Group contactGroup = getGroup();
		BdfDictionary oldMetadata = BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 1),
				new BdfEntry("local", true)
		);
		final MessageId localGroupUpdateId = new MessageId(getRandomId());
		final Map<MessageId, BdfDictionary> localGroupMessageMetadata =
				Collections.singletonMap(localGroupUpdateId, oldMetadata);
		final MessageId contactGroupUpdateId = new MessageId(getRandomId());
		final Map<MessageId, BdfDictionary> contactGroupMessageMetadata =
				Collections.singletonMap(contactGroupUpdateId, oldMetadata);
		final BdfList oldUpdate = BdfList.of("foo", 1, BdfDictionary.of(
				new BdfEntry("fooKey1", "oldFooValue1")
		));

		context.checking(new Expectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			// Merge the new properties with the existing properties
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(localGroupMessageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localGroupUpdateId);
			will(returnValue(oldUpdate));
			// Store the merged properties in the local group, version 2
			expectStoreMessage(txn, localGroup.getId(), "foo",
					fooPropertiesDict, 2, true, false);
			// Delete the previous update
			oneOf(db).removeMessage(txn, localGroupUpdateId);
			// Store the merged properties in each contact's group, version 2
			oneOf(db).getContacts(txn);
			will(returnValue(Collections.singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(contactGroupMessageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), "foo",
					fooPropertiesDict, 2, true, true);
			// Delete the previous update
			oneOf(db).removeMessage(txn, contactGroupUpdateId);
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), fooProperties);
	}

	private Group getGroup() {
		GroupId g = new GroupId(getRandomId());
		byte[] descriptor = getRandomBytes(MAX_GROUP_DESCRIPTOR_LENGTH);
		return new Group(g, CLIENT_ID, descriptor);
	}

	private LocalAuthor getLocalAuthor() {
		AuthorId id = new AuthorId(getRandomId());
		String name = getRandomString(MAX_AUTHOR_NAME_LENGTH);
		byte[] publicKey = getRandomBytes(MAX_PUBLIC_KEY_LENGTH);
		byte[] privateKey = getRandomBytes(MAX_PUBLIC_KEY_LENGTH);
		long created = System.currentTimeMillis();
		return new LocalAuthor(id, name, publicKey, privateKey, created);
	}

	private Contact getContact(boolean active) {
		ContactId c = new ContactId(nextContactId++);
		AuthorId a = new AuthorId(getRandomId());
		String name = getRandomString(MAX_AUTHOR_NAME_LENGTH);
		byte[] publicKey = getRandomBytes(MAX_PUBLIC_KEY_LENGTH);
		return new Contact(c, new Author(a, name, publicKey),
				localAuthor.getId(), true, active);
	}

	private Message getMessage(GroupId g, long timestamp) {
		MessageId messageId = new MessageId(getRandomId());
		byte[] raw = getRandomBytes(MAX_MESSAGE_BODY_LENGTH);
		return new Message(messageId, g, timestamp, raw);
	}

	private void expectGetLocalProperties(final Transaction txn)
			throws Exception {
		final Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<MessageId, BdfDictionary>();
		// The only update for transport "foo" should be returned
		final MessageId fooVersion999 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion999, BdfDictionary.of(
				new BdfEntry("transportId", "foo"),
				new BdfEntry("version", 999)
		));
		// An old update for transport "bar" should be deleted
		final MessageId barVersion2 = new MessageId(getRandomId());
		messageMetadata.put(barVersion2, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 2)
		));
		// An even older update for transport "bar" should be deleted
		final MessageId barVersion1 = new MessageId(getRandomId());
		messageMetadata.put(barVersion1, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 1)
		));
		// The latest update for transport "bar" should be returned
		final MessageId barVersion3 = new MessageId(getRandomId());
		messageMetadata.put(barVersion3, BdfDictionary.of(
				new BdfEntry("transportId", "bar"),
				new BdfEntry("version", 3)
		));
		final BdfList fooUpdate = BdfList.of("foo", 999, fooPropertiesDict);
		final BdfList barUpdate = BdfList.of("bar", 3, barPropertiesDict);

		context.checking(new Expectations() {{
			// Find the latest local update for each transport
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(db).removeMessage(txn, barVersion1);
			oneOf(db).removeMessage(txn, barVersion2);
			// Retrieve and parse the latest local properties
			oneOf(clientHelper).getMessageAsList(txn, fooVersion999);
			will(returnValue(fooUpdate));
			oneOf(clientHelper).getMessageAsList(txn, barVersion3);
			will(returnValue(barUpdate));
		}});
	}

	private void expectStoreMessage(final Transaction txn, final GroupId g,
			String transportId, final BdfDictionary properties, long version,
			boolean local, final boolean shared) throws Exception {
		final long timestamp = 123456789;
		final BdfList body = BdfList.of(transportId, version, properties);
		final Message message = getMessage(g, timestamp);
		final BdfDictionary meta = BdfDictionary.of(
				new BdfEntry("transportId", transportId),
				new BdfEntry("version", version),
				new BdfEntry("local", local)
		);

		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(timestamp));
			oneOf(clientHelper).createMessage(g, timestamp, body);
			will(returnValue(message));
			oneOf(clientHelper).addLocalMessage(txn, message, meta, shared);
		}});
	}
}