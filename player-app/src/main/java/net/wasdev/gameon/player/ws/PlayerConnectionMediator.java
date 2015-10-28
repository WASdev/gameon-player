/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.player.ws;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.enterprise.concurrent.ManagedThreadFactory;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.websocket.Session;

/**
 * A session that buffers content destined for the client devices across
 * connect/disconnects.
 */
public class PlayerConnectionMediator implements Runnable {

	private final String userId;
	private final String username;
	private final String id = UUID.randomUUID().toString();
	private final ThreadFactory threadFactory;
	private final ConciergeClient concierge;

	private Session clientSession = null;
	private Thread clientThread = null;
	private volatile boolean keepGoing = true;

	private String roomId = null;
	private RoomMediator currentRoom = null;

	private AtomicInteger suspendCount = new AtomicInteger(0);

	/** Queue of messages destined for the client device */
	private final LinkedBlockingDeque<String> toClient = new LinkedBlockingDeque<String>();

	/**
	 * Create a new PlayerSession for the user.
	 * @param userId Name of user for this session
	 * @param threadFactory
	 */
	public PlayerConnectionMediator(String userId, String username, ManagedThreadFactory threadFactory, ConciergeClient concierge) {
		this.userId = userId;
		this.username = username;
		this.threadFactory = threadFactory;
		this.concierge = concierge;
		Log.log(Level.FINEST, this, "playerConnectionMediator built. currentRoom should be null at the mo.. is it? "+(currentRoom==null));
	}

	/**
	 * @return ID of this session
	 */
	public String getId() {
		return id;
	}

	public void sendToRoom(String[] routing) {
		this.sendToRoom(currentRoom, routing);
	}

	/**
	 * Given room,&lt;roomId&gt;,{...}, make sure the specified room id
	 * matches the target room.
	 * @param targetRoom Room to write to (like the old room)
	 * @param routing Array of 3 elements: room*,&lt;roomId&gt;,{json payload}
	 *
	 */
	public void sendToRoom(RoomMediator targetRoom, String[] routing) {
		if ( Constants.SOS.equals(routing[0])) {
			switchRooms(routing);
		} else if ( targetRoom.getId().equals(routing[1]) ){
			// send messages for the current room on to the room (others fall on the floor)
			targetRoom.route(routing);
		} else {
			Log.log(Level.FINEST, this, "sendToRoom -- Dropping message {0} intended for {1} but currently targetting room {2}", Arrays.asList(routing), String.valueOf(routing[1]), String.valueOf(targetRoom.getId()));
		}
	}

	/**
	 * Dedicated thread writing to the client connection
	 */
	@Override
	public void run() {
		// Dedicated thread sending messages back to the client as fast
		// as it can take them: maybe we batch these someday.
		while( keepGoing ) {
			try {
				String message = toClient.take();
				Log.log(Level.FINEST, this, "Sending to client: {0}", message);

				if ( !ConnectionUtils.sendText(clientSession, message) ) {
					// If the send failed, tuck the message back in the head of the queue.
					toClient.offerFirst(message);
				}
			} catch (InterruptedException ex) {
				if ( keepGoing ) {
					Thread.interrupted();
					keepGoing = false;
				}
			}
		}
		Log.log(Level.FINER, this, "Exit client writer thread {0}", this);
	}

	/**
	 * Compose an acknowledgement to send back to the client that
	 * contains the mediator id.
	 *
	 * @return ack message with mediator id
	 */
	public void sendClientAck() {
		JsonObject ack = Json.createObjectBuilder()
				.add(Constants.MEDIATOR_ID, id)
				.add(Constants.ROOM_ID, roomId)
				.build();

		toClient.add("ack," + ack.toString());
	}

	/**
	 * Add message to queue to return to client
	 * @param routing
	 */
	public void sendToClient(String[] routing) {
		// make sure we're only dealing with messages for everyone,
		// or messages for this user (ignore all others)
		if ( "*".equals(routing[1]) || userId.equals(routing[1])){
			// TODO: Capacity?
			toClient.offer(String.join(",", routing));

			if ( Constants.PLAYER_LOCATION.equals(routing[0])) {
				switchRooms(routing);
			}
		} else {
			Log.log(Level.FINEST, this, "sendToClient -- Dropping message {0}", Arrays.asList(routing));
		}
	}

	/**
	 *
	 * @param clientSession
	 * @param roomId2
	 * @param lastmessage
	 */
	public boolean initializeConnection(Session clientSession, String roomId, long lastmessage) {
		this.clientSession = clientSession;

		RoomMediator newRoom = concierge.checkin(null, currentRoom, roomId == null ? Constants.FIRST_ROOM : roomId);

		// Get connection to the room (resets vars, does good things)
		if ( connectToRoom(newRoom, true) ) {
			suspendCount.set(0); // resumed!

			// set up delivery thread
			clientThread = threadFactory.newThread(this);
			clientThread.start();

			return true;
		}

		return false;
	}

	private void switchRooms(String[] routing) {
		Log.log(Level.FINER, this, "SWITCH ROOMS");

		RoomMediator newRoom;
		RoomMediator oldRoom = currentRoom;
		boolean SOS = Constants.SOS.equals(routing[0]);

		// Disconnect from the current room: stop receiving additional messages
		oldRoom.unsubscribe(this);

		if ( SOS ) {
			toClient.offer(String.format(Constants.LIFE_RING, userId));
		}

		// Send the client to a transitional place. They might sit here awhile waiting for connection to new room
		toClient.offer(String.format(Constants.PART, userId, oldRoom.getId()));
		toClient.offer(String.format(Constants.NETHER_REGION, userId));

		if ( SOS || Constants.FIRST_ROOM.equals(oldRoom.getId())) {
			// For an SOS or for First room, we don't care about the current room's exits,
			// we need to find a brand new starter room
			newRoom = concierge.changeRooms(oldRoom, null);
		} else {
			// If we are properly exiting a room, we have the new room in the payload
			// of the message from the old room.
			JsonReader jsonReader = Json.createReader(new StringReader(routing[2]));
			JsonObject exitData = jsonReader.readObject();
			String exitId = exitData.getString("exitId");

			newRoom = concierge.changeRooms(oldRoom, exitId);
		}

		// Try to connect to the new room; safe to fallback to a new/random room if
		// we can't connect to the room the concierge told us to use
		if ( connectToRoom(newRoom, true) ) {
			Log.log(Level.FINER, this, "GOODBYE {0}", oldRoom.getId());

			// We connected to the new room! Say goodbye to the old room
			sendToRoom(oldRoom, new String[] {Constants.ROOM_GOODBYE, oldRoom.getId(),
					Json.createObjectBuilder()
					.add(Constants.USERNAME, username)
					.add(Constants.USER_ID, userId).build().toString() });
		} else {
			// recover: back into the old room -- no fallback
			connectToRoom(oldRoom, false);
		}
	}

	private boolean connectToRoom(RoomMediator room, boolean fallback) {
		Set<String> visitedRooms = new HashSet<String>();
		visitedRooms.add(room.getId());

		// SUBSCRIBE: Open the connection to receive notifications from the room
		while ( !room.subscribe(this, 0) ) {
			if ( !fallback || !visitedRooms.add(room.getId()) ) {
				// either no fallback, or no new room to try :(
				return false;
			}
			room = concierge.changeRooms(room, null);
		}

		Log.log(Level.FINER, this, "playerConnectionMediator just set room for {0} to be {1}", userId, room.getId());
		this.currentRoom = room;
		this.roomId = room.getId();

		Log.log(Level.FINER, this, "HELLO {0}", room.getId());
		toClient.offer(String.format(Constants.JOIN, userId, room.getId()));
		sendToRoom(currentRoom, new String[] {Constants.ROOM_HELLO, room.getId(),
				Json.createObjectBuilder()
				.add(Constants.USERNAME, username)
				.add(Constants.USER_ID, userId).build().toString() });

		sendClientAck(); // update client room
		return true; // connected
	}

	public void reconnectToRoom() {
		if( !connectToRoom(currentRoom, false) ) {
			// not able to reconnect to room, initiate a real switch
			// complete with join/part messages
			switchRooms(new String[] { Constants.SOS });
		}
	}

	/**
	 * This is strictly about pulling from the queue
	 * to send to the client: we're trying to cover short gaps
	 * in connection from the client (within reason)
	 */
	public void disconnect() {
		keepGoing = false;
		if ( clientThread != null)
			clientThread.interrupt();
	}

	/**
	 *
	 */
	public void destroy() {
		Log.log(Level.FINE, this, "session {0} destroyed", userId);
		// session expired.
		toClient.clear();
		
		Log.log(Level.FINER, this, "playerConnectionMediator for {1} unsubscribing from currentRoom {0} and setting it to null", currentRoom.getId(), userId);
		
		currentRoom.unsubscribe(this);
		currentRoom = null;
	}

	@Override
	public String toString() {
		return this.getClass().getName() + "[roomId=" + roomId + ", userId=" + userId + ", suspendCount=" + suspendCount.get() +"]";
	}

	public int incrementAndGet() {
		return suspendCount.incrementAndGet();
	}
}
