package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.mailbox.MailboxAuthToken;
import org.briarproject.bramble.api.mailbox.MailboxFolderId;
import org.briarproject.bramble.api.mailbox.MailboxProperties;
import org.briarproject.bramble.api.mailbox.MailboxSettingsManager;
import org.briarproject.bramble.api.mailbox.MailboxUpdate;
import org.briarproject.bramble.api.mailbox.MailboxUpdateWithMailbox;
import org.briarproject.bramble.api.mailbox.MailboxVersion;
import org.briarproject.bramble.api.mailbox.event.RemoteMailboxUpdateEvent;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.CLIENT_ID;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.GROUP_KEY_SENT_CLIENT_SUPPORTS;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.MAJOR_VERSION;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.MSG_KEY_LOCAL;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.MSG_KEY_VERSION;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.PROP_KEY_AUTHTOKEN;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.PROP_KEY_INBOXID;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.PROP_KEY_ONION;
import static org.briarproject.bramble.api.mailbox.MailboxUpdateManager.PROP_KEY_OUTBOXID;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.bramble.test.TestUtils.getContact;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.hasEvent;
import static org.briarproject.bramble.test.TestUtils.mailboxUpdateEqual;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MailboxUpdateManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ClientVersioningManager clientVersioningManager =
			context.mock(ClientVersioningManager.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final Clock clock = context.mock(Clock.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final MailboxSettingsManager mailboxSettingsManager =
			context.mock(MailboxSettingsManager.class);

	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final BdfDictionary propsDict;
	private final BdfDictionary emptyPropsDict = new BdfDictionary();
	private final List<MailboxVersion> someClientSupportsList;
	private final BdfList someClientSupports;
	private final List<MailboxVersion> newerClientSupportsList;
	private final BdfList newerClientSupports;
	private final List<MailboxVersion> someServerSupportsList;
	private final BdfList someServerSupports;
	private final BdfList emptyServerSupports = new BdfList();
	private final MailboxUpdateWithMailbox updateWithMailbox;
	private final MailboxUpdate updateNoMailbox;
	private final MailboxProperties ownProps;

	public MailboxUpdateManagerImplTest() {
		Random rnd = new Random();
		someClientSupportsList = singletonList(new MailboxVersion(
				rnd.nextInt(), rnd.nextInt()));
		someClientSupports = BdfList.of(BdfList.of(
				someClientSupportsList.get(0).getMajor(),
				someClientSupportsList.get(0).getMinor()));
		newerClientSupportsList = singletonList(new MailboxVersion(
				someClientSupportsList.get(0).getMajor(),
				someClientSupportsList.get(0).getMinor() + 1));
		newerClientSupports = BdfList.of(BdfList.of(
				newerClientSupportsList.get(0).getMajor(),
				newerClientSupportsList.get(0).getMinor()));

		someServerSupportsList = singletonList(new MailboxVersion(
				rnd.nextInt(), rnd.nextInt()));
		someServerSupports = BdfList.of(BdfList.of(
				someServerSupportsList.get(0).getMajor(),
				someServerSupportsList.get(0).getMinor()));

		updateNoMailbox = new MailboxUpdate(someClientSupportsList);

		ownProps = new MailboxProperties("http://bar.onion",
				new MailboxAuthToken(getRandomId()), true,
				someServerSupportsList);
		updateWithMailbox = new MailboxUpdateWithMailbox(someClientSupportsList,
				someServerSupportsList, ownProps.getOnion(),
				new MailboxAuthToken(getRandomId()),
				new MailboxFolderId(getRandomId()),
				new MailboxFolderId(getRandomId()));
		propsDict = new BdfDictionary();
		propsDict.put(PROP_KEY_ONION, updateWithMailbox.getOnion());
		propsDict.put(PROP_KEY_AUTHTOKEN, updateWithMailbox.getAuthToken()
				.getBytes());
		propsDict.put(PROP_KEY_INBOXID, updateWithMailbox.getInboxId()
				.getBytes());
		propsDict.put(PROP_KEY_OUTBOXID, updateWithMailbox.getOutboxId()
				.getBytes());
	}

	private MailboxUpdateManagerImpl createInstance(
			List<MailboxVersion> clientSupports) {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
		}});
		return new MailboxUpdateManagerImpl(clientSupports, db,
				clientHelper, clientVersioningManager, metadataParser,
				contactGroupFactory, clock, mailboxSettingsManager, crypto);
	}

	@Test
	public void testCreatesGroupsAtUnpairedStartup() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		BdfDictionary sentDict = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_SENT_CLIENT_SUPPORTS,
				someClientSupports));

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			// addingContact()
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(SHARED));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(mailboxSettingsManager).getOwnMailboxProperties(txn);
			will(returnValue(null));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 1, someClientSupports,
					emptyServerSupports, emptyPropsDict, true);

			oneOf(clientHelper).mergeGroupMetadata(txn, localGroup.getId(),
					sentDict);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.onDatabaseOpened(txn);
	}

	@Test
	public void testCreatesGroupsAndCreatesAndSendsAtPairedStartup()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		BdfDictionary sentDict = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_SENT_CLIENT_SUPPORTS,
				someClientSupports));

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			// addingContact()
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(SHARED));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(mailboxSettingsManager).getOwnMailboxProperties(txn);
			will(returnValue(ownProps));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getAuthToken()));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getInboxId()));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getOutboxId()));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 1, someClientSupports,
					someServerSupports, propsDict, true);

			oneOf(clientHelper).mergeGroupMetadata(txn, localGroup.getId(),
					sentDict);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.onDatabaseOpened(txn);
	}

	@Test
	public void testUnchangedClientSupportsOnSecondStartup() throws Exception {
		Transaction txn = new Transaction(null, false);

		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		Map<MessageId, BdfDictionary> emptyMessageMetadata =
				new LinkedHashMap<>();
		BdfDictionary sentDict = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_SENT_CLIENT_SUPPORTS,
				someClientSupports));

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			// addingContact()
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(SHARED));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(mailboxSettingsManager).getOwnMailboxProperties(txn);
			will(returnValue(null));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(emptyMessageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 1, someClientSupports,
					emptyServerSupports, emptyPropsDict, true);

			oneOf(clientHelper).mergeGroupMetadata(txn, localGroup.getId(),
					sentDict);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.onDatabaseOpened(txn);

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(true));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(sentDict));
			oneOf(clientHelper).parseMailboxVersionList(someClientSupports);
			will(returnValue(someClientSupportsList));
		}});

		t = createInstance(someClientSupportsList);
		t.onDatabaseOpened(txn);
	}

	@Test
	public void testSendsUpdateWhenClientSupportsChangedOnSecondStartup()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		Map<MessageId, BdfDictionary> emptyMessageMetadata =
				new LinkedHashMap<>();
		BdfDictionary sentDict = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_SENT_CLIENT_SUPPORTS,
				someClientSupports));

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			// addingContact()
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(SHARED));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(mailboxSettingsManager).getOwnMailboxProperties(txn);
			will(returnValue(null));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(emptyMessageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 1, someClientSupports,
					emptyServerSupports, emptyPropsDict, true);

			oneOf(clientHelper).mergeGroupMetadata(txn, localGroup.getId(),
					sentDict);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.onDatabaseOpened(txn);

		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId messageId = new MessageId(getRandomId());
		messageMetadata.put(messageId, metaDictionary);
		BdfList body = BdfList.of(1, someClientSupports, someServerSupports,
				propsDict);
		BdfDictionary newerSentDict = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_SENT_CLIENT_SUPPORTS,
				newerClientSupports));

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(true));

			// Find out that we are now on newerClientSupportsList
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(sentDict));
			oneOf(clientHelper).parseMailboxVersionList(someClientSupports);
			will(returnValue(someClientSupportsList));

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));

			// getLocalUpdate()
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, messageId);
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, someServerSupports, propsDict);
			will(returnValue(updateWithMailbox));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));

			// storeMessageReplaceLatest()
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 2,
					newerClientSupports, someServerSupports, propsDict, true);
			oneOf(db).removeMessage(txn, messageId);

			oneOf(clientHelper).mergeGroupMetadata(txn, localGroup.getId(),
					newerSentDict);
		}});

		t = createInstance(newerClientSupportsList);
		t.onDatabaseOpened(txn);
	}

	@Test
	public void testCreatesContactGroupWhenAddingContactUnpaired()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();

		context.checking(new Expectations() {{
			// Create the group and share it with the contact
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(SHARED));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(mailboxSettingsManager).getOwnMailboxProperties(txn);
			will(returnValue(null));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 1, someClientSupports,
					emptyServerSupports, emptyPropsDict, true);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.addingContact(txn, contact);
	}

	@Test
	public void testCreatesContactGroupAndCreatesAndSendsWhenAddingContactPaired()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();

		context.checking(new Expectations() {{
			// Create the group and share it with the contact
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(SHARED));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(mailboxSettingsManager).getOwnMailboxProperties(txn);
			will(returnValue(ownProps));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getAuthToken()));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getInboxId()));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getOutboxId()));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 1, someClientSupports,
					someServerSupports, propsDict, true);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.addingContact(txn, contact);
	}

	@Test
	public void testRemovesGroupWhenRemovingContact() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).removeGroup(txn, contactGroup);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.removingContact(txn, contact);
	}

	@Test
	public void testDoesNotDeleteAnythingWhenFirstUpdateIsDelivered()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		GroupId contactGroupId = new GroupId(getRandomId());
		Message message = getMessage(contactGroupId);
		BdfList body = BdfList.of(1, someClientSupports, someServerSupports,
				propsDict);
		Metadata meta = new Metadata();
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		// A local update should be ignored
		MessageId localUpdateId = new MessageId(getRandomId());
		messageMetadata.put(localUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getContactId(txn, contactGroupId);
			will(returnValue(contact.getId()));
			oneOf(clientHelper).getMessageAsList(txn, message.getId());
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, someServerSupports, propsDict);
			will(returnValue(updateWithMailbox));
			oneOf(db).resetUnackedMessagesToSend(txn, contact.getId());
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				t.incomingMessage(txn, message, meta));
		assertTrue(hasEvent(txn, RemoteMailboxUpdateEvent.class));
	}

	@Test
	public void testDeletesOlderUpdateWhenUpdateIsDelivered()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		GroupId contactGroupId = new GroupId(getRandomId());
		Message message = getMessage(contactGroupId);
		BdfList body = BdfList.of(1, someClientSupports, someServerSupports,
				propsDict);
		Metadata meta = new Metadata();
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 2),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		// This older version should be deleted
		MessageId updateId = new MessageId(getRandomId());
		messageMetadata.put(updateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));
		// A local update should be ignored
		MessageId localUpdateId = new MessageId(getRandomId());
		messageMetadata.put(localUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
			oneOf(db).deleteMessage(txn, updateId);
			oneOf(db).deleteMessageMetadata(txn, updateId);
			oneOf(clientHelper).getContactId(txn, contactGroupId);
			will(returnValue(contact.getId()));
			oneOf(clientHelper).getMessageAsList(txn, message.getId());
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, someServerSupports, propsDict);
			will(returnValue(updateWithMailbox));
			oneOf(db).resetUnackedMessagesToSend(txn, contact.getId());
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				t.incomingMessage(txn, message, meta));
		assertTrue(hasEvent(txn, RemoteMailboxUpdateEvent.class));
	}

	@Test
	public void testDeletesObsoleteUpdateWhenDelivered() throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId contactGroupId = new GroupId(getRandomId());
		Message message = getMessage(contactGroupId);
		Metadata meta = new Metadata();
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 3),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		// This newer version should not be deleted
		MessageId updateId = new MessageId(getRandomId());
		messageMetadata.put(updateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 4),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
			oneOf(db).deleteMessage(txn, message.getId());
			oneOf(db).deleteMessageMetadata(txn, message.getId());
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				t.incomingMessage(txn, message, meta));
		assertFalse(hasEvent(txn, RemoteMailboxUpdateEvent.class));
	}

	@Test
	public void testCreatesAndStoresLocalUpdateWithNewVersionOnPairing()
			throws Exception {
		Contact contact = getContact();
		List<Contact> contacts = singletonList(contact);
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		Transaction txn = new Transaction(null, false);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId latestId = new MessageId(getRandomId());
		messageMetadata.put(latestId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));
		// Some remote props, ignored
		messageMetadata.put(new MessageId(getRandomId()), BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 3),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		context.checking(new Expectations() {{
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getAuthToken()));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getInboxId()));
			oneOf(crypto).generateUniqueId();
			will(returnValue(updateWithMailbox.getOutboxId()));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 2, someClientSupports,
					someServerSupports, propsDict, true);
			oneOf(db).removeMessage(txn, latestId);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.mailboxPaired(txn, ownProps.getOnion(), someServerSupportsList);
	}

	@Test
	public void testStoresLocalUpdateNoMailboxWithNewVersionOnUnpairing()
			throws Exception {
		Contact contact = getContact();
		List<Contact> contacts = singletonList(contact);
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		Transaction txn = new Transaction(null, false);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId latestId = new MessageId(getRandomId());
		messageMetadata.put(latestId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));
		// Some remote props, ignored
		messageMetadata.put(new MessageId(getRandomId()), BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 3),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		context.checking(new Expectations() {{
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			expectStoreMessage(txn, contactGroup.getId(), 2, someClientSupports,
					emptyServerSupports, emptyPropsDict, true);
			oneOf(db).removeMessage(txn, latestId);
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		t.mailboxUnpaired(txn);
	}

	@Test
	public void testGetRemoteUpdate() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId messageId = new MessageId(getRandomId());
		messageMetadata.put(messageId, metaDictionary);
		BdfList body = BdfList.of(1, someClientSupports, someServerSupports,
				propsDict);

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory)
					.createContactGroup(CLIENT_ID, MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, messageId);
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, someServerSupports, propsDict);
			will(returnValue(updateWithMailbox));
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		MailboxUpdate remote = t.getRemoteUpdate(txn, contact.getId());
		assertTrue(mailboxUpdateEqual(remote, updateWithMailbox));
	}

	@Test
	public void testGetRemoteUpdateReturnsNullBecauseNoUpdate()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		Map<MessageId, BdfDictionary> emptyMessageMetadata =
				new LinkedHashMap<>();

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(emptyMessageMetadata));
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		assertNull(t.getRemoteUpdate(txn, contact.getId()));
	}

	@Test
	public void testGetRemoteUpdateNoMailbox() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId messageId = new MessageId(getRandomId());
		messageMetadata.put(messageId, metaDictionary);
		BdfList body = BdfList.of(1, someClientSupports, emptyServerSupports,
				emptyPropsDict);

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, messageId);
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, emptyServerSupports, emptyPropsDict);
			will(returnValue(updateNoMailbox));
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		MailboxUpdate remote = t.getRemoteUpdate(txn, contact.getId());
		assertTrue(mailboxUpdateEqual(remote, updateNoMailbox));
	}

	@Test
	public void testGetLocalUpdate() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId messageId = new MessageId(getRandomId());
		messageMetadata.put(messageId, metaDictionary);
		BdfList body = BdfList.of(1, someClientSupports, someServerSupports,
				propsDict);

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, messageId);
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, someServerSupports, propsDict);
			will(returnValue(updateWithMailbox));
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		MailboxUpdate local = t.getLocalUpdate(txn, contact.getId());
		assertTrue(mailboxUpdateEqual(local, updateWithMailbox));
	}

	@Test
	public void testGetLocalUpdateNoMailbox() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		);
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();
		MessageId messageId = new MessageId(getRandomId());
		messageMetadata.put(messageId, metaDictionary);
		BdfList body = BdfList.of(1, someClientSupports, emptyServerSupports,
				emptyPropsDict);

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, messageId);
			will(returnValue(body));
			oneOf(clientHelper).parseAndValidateMailboxUpdate(
					someClientSupports, emptyServerSupports, emptyPropsDict);
			will(returnValue(updateNoMailbox));
		}});

		MailboxUpdateManagerImpl t = createInstance(someClientSupportsList);
		MailboxUpdate local = t.getLocalUpdate(txn, contact.getId());
		assertTrue(mailboxUpdateEqual(local, updateNoMailbox));
	}

	private void expectStoreMessage(Transaction txn, GroupId g,
			long version, BdfList clientSupports, BdfList serverSupports,
			BdfDictionary properties, boolean local)
			throws Exception {
		BdfList body = BdfList.of(version, clientSupports, serverSupports,
				properties);
		Message message = getMessage(g);
		long timestamp = message.getTimestamp();
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_VERSION, version),
				new BdfEntry(MSG_KEY_LOCAL, local)
		);

		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(timestamp));
			oneOf(clientHelper).createMessage(g, timestamp, body);
			will(returnValue(message));
			oneOf(clientHelper).addLocalMessage(txn, message, meta, true,
					false);
		}});
	}
}
