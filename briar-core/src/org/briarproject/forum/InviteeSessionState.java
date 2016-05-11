package org.briarproject.forum;

import org.briarproject.api.clients.SessionId;
import org.briarproject.api.contact.ContactId;
import org.briarproject.api.data.BdfDictionary;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;

import static org.briarproject.api.forum.ForumConstants.IS_SHARER;
import static org.briarproject.api.forum.ForumConstants.SHARE_MSG_TYPE_ABORT;
import static org.briarproject.api.forum.ForumConstants.SHARE_MSG_TYPE_ACCEPT;
import static org.briarproject.api.forum.ForumConstants.SHARE_MSG_TYPE_DECLINE;
import static org.briarproject.api.forum.ForumConstants.SHARE_MSG_TYPE_INVITATION;
import static org.briarproject.api.forum.ForumConstants.SHARE_MSG_TYPE_LEAVE;
import static org.briarproject.api.forum.ForumConstants.STATE;
import static org.briarproject.forum.InviteeSessionState.Action.LOCAL_ACCEPT;
import static org.briarproject.forum.InviteeSessionState.Action.LOCAL_DECLINE;
import static org.briarproject.forum.InviteeSessionState.Action.LOCAL_LEAVE;
import static org.briarproject.forum.InviteeSessionState.Action.REMOTE_INVITATION;
import static org.briarproject.forum.InviteeSessionState.Action.REMOTE_LEAVE;

// This class is not thread-safe
public class InviteeSessionState extends ForumSharingSessionState {

	private State state;

	public InviteeSessionState(SessionId sessionId, MessageId storageId,
			GroupId groupId, State state, ContactId contactId, GroupId forumId,
			String forumName, byte[] forumSalt) {

		super(sessionId, storageId, groupId, contactId, forumId, forumName,
				forumSalt);
		this.state = state;
	}

	public BdfDictionary toBdfDictionary() {
		BdfDictionary d = super.toBdfDictionary();
		d.put(STATE, getState().getValue());
		d.put(IS_SHARER, false);
		return d;
	}

	public void setState(State state) {
		this.state = state;
	}

	public State getState() {
		return state;
	}

	public enum State {
		ERROR(0),
		AWAIT_INVITATION(1) {
			@Override
			public State next(Action a) {
				if (a == REMOTE_INVITATION) return AWAIT_LOCAL_RESPONSE;
				return ERROR;
			}
		},
		AWAIT_LOCAL_RESPONSE(2) {
			@Override
			public State next(Action a) {
				if (a == LOCAL_ACCEPT || a == LOCAL_DECLINE) return FINISHED;
				if (a == REMOTE_LEAVE) return LEFT;
				return ERROR;
			}
		},
		FINISHED(3) {
			@Override
			public State next(Action a) {
				if (a == LOCAL_LEAVE || a == REMOTE_LEAVE) return LEFT;
				return FINISHED;
			}
		},
		LEFT(4) {
			@Override
			public State next(Action a) {
				if (a == LOCAL_LEAVE) return ERROR;
				return LEFT;
			}
		};

		private final int value;

		State(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}

		public static State fromValue(int value) {
			for (State s : values()) {
				if (s.value == value) return s;
			}
			throw new IllegalArgumentException();
		}

		public State next(Action a) {
			return this;
		}
	}

	public enum Action {
		LOCAL_ACCEPT,
		LOCAL_DECLINE,
		LOCAL_LEAVE,
		LOCAL_ABORT,
		REMOTE_INVITATION,
		REMOTE_LEAVE,
		REMOTE_ABORT;

		public static Action getLocal(long type) {
			if (type == SHARE_MSG_TYPE_ACCEPT) return LOCAL_ACCEPT;
			if (type == SHARE_MSG_TYPE_DECLINE) return LOCAL_DECLINE;
			if (type == SHARE_MSG_TYPE_LEAVE) return LOCAL_LEAVE;
			if (type == SHARE_MSG_TYPE_ABORT) return LOCAL_ABORT;
			return null;
		}

		public static Action getRemote(long type) {
			if (type == SHARE_MSG_TYPE_INVITATION) return REMOTE_INVITATION;
			if (type == SHARE_MSG_TYPE_LEAVE) return REMOTE_LEAVE;
			if (type == SHARE_MSG_TYPE_ABORT) return REMOTE_ABORT;
			return null;
		}
	}

}