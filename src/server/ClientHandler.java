package server;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * ClientHandler - one instance per connected client. Runs on its own thread.
 *
 * Uses DataInputStream + DataOutputStream (readUTF / writeUTF) to talk to the
 * client. Each call to readUTF reads exactly one message that the client sent
 * with writeUTF, so messages never get split or merged.
 *
 * Protocol (each message is one writeUTF call):
 *
 *   Client -> Server:
 *     NAME <username>
 *     PICK <story title>
 *     SENTENCE <the sentence>
 *     LEAVE
 *     CREATE_STORY <title>|<opening sentence>
 *
 *   Server -> Client:
 *     STORIES <title1>|<title2>|<title3>
 *     STORY <current text of the story>
 *     ROUND <turnsTaken> <maxTurns>
 *     YOUR_TURN
 *     WAIT
 *     END
 *     INFO <message>
 *     BACK_TO_LOBBY
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private DataInputStream din;
    private DataOutputStream dout;
    private String username;
    private Story currentStory;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    public String getUsername() {
        return username;
    }

    // Send one message to this client.
    public synchronized void send(String message) {
        try {
            if (dout != null) {
                dout.writeUTF(message);
                dout.flush();
            }
        } catch (IOException e) {
            // Client probably dropped; ignore here, run() handles cleanup.
        }
    }

    @Override
    public void run() {
        try {
            din  = new DataInputStream(socket.getInputStream());
            dout = new DataOutputStream(socket.getOutputStream());

            // Step 1: read the username.
            String line = din.readUTF();
            if (!line.startsWith("NAME ")) {
                socket.close();
                return;
            }
            username = line.substring(5).trim();
            System.out.println(username + " joined the server.");

            // Step 2: send the list of available stories.
            sendStoryList();

            // Step 3: read messages from the client until they disconnect.
            while (true) {
                String msg = din.readUTF();
                if (msg.startsWith("PICK ")) {
                    String title = msg.substring(5).trim();
                    handlePick(title);
                } else if (msg.startsWith("SENTENCE ")) {
                    String sentence = msg.substring(9).trim();
                    handleSentence(sentence);
                } else if (msg.startsWith("CREATE_STORY ")) {
                    String payload = msg.substring(13).trim();
                    handleCreateStory(payload);
                } else if (msg.equals("LEAVE")) {
                    handleLeave();
                }
            }
        } catch (IOException e) {
            System.out.println("Connection lost: " + username);
        } finally {
            // Clean up when client disconnects.
            StoryServer.removeHandler(this);
            if (currentStory != null) {
                currentStory.removeClient(this);
                // If it was this client's turn, pass to the next one.
                promptNext(currentStory);
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // Send list of available stories to this client.
    private void sendStoryList() {
        Map<String, Story> stories = StoryServer.getStories();
        StringBuilder sb = new StringBuilder("STORIES ");
        boolean first = true;
        for (String title : stories.keySet()) {
            if (!first) sb.append("|");
            sb.append(title);
            first = false;
        }
        send(sb.toString());
    }

    // Client picked a story to join.
    private void handlePick(String title) {
        Story story = StoryServer.getStories().get(title);
        if (story == null) {
            send("INFO Story not found.");
            return;
        }
        currentStory = story;
        story.addClient(this);

        // Send the current story text to this new client.
        send("STORY " + story.getText());
        send("ROUND " + story.getTurnsTaken() + " " + Story.MAX_TURNS);
        send("INFO Joined story: " + title);

        if (story.isFinished()) {
            send("END");
            return;
        }

        // If this is the only client, prompt them right away.
        // Otherwise, tell them to wait (someone else might be writing now).
        if (story.size() == 1) {
            send("YOUR_TURN");
        } else {
            send("WAIT");
        }
    }

    // Client sent a sentence to add to the current story.
    private void handleSentence(String sentence) {
        if (currentStory == null || sentence.isEmpty()) return;
        if (currentStory.isFinished()) {
            send("INFO Game is already over.");
            send("END");
            return;
        }

        currentStory.addSentence(sentence);

        // Broadcast the updated story + round count to everyone in this story.
        String fullText = currentStory.getText();
        int taken = currentStory.getTurnsTaken();
        for (ClientHandler c : currentStory.getAllClients()) {
            c.send("STORY " + fullText);
            c.send("ROUND " + taken + " " + Story.MAX_TURNS);
            c.send("WAIT");
        }

        if (currentStory.isFinished()) {
            // No more turns — tell everyone the game is over.
            for (ClientHandler c : currentStory.getAllClients()) {
                c.send("INFO The story is complete.");
                c.send("END");
            }
            return;
        }

        // Pick the next client in the round-robin queue.
        promptNext(currentStory);
    }

    // Client wants to create a new story.
    // Payload format: <title>|<opening sentence>
    private void handleCreateStory(String payload) {
        int sep = payload.indexOf('|');
        if (sep < 1 || sep == payload.length() - 1) {
            send("INFO Invalid format. Use: title|opening sentence");
            return;
        }
        String title   = payload.substring(0, sep).trim();
        String opening = payload.substring(sep + 1).trim();
        if (title.isEmpty() || opening.isEmpty()) {
            send("INFO Title and opening sentence must not be empty.");
            return;
        }
        Map<String, Story> stories = StoryServer.getStories();
        if (stories.containsKey(title)) {
            send("INFO A story named \"" + title + "\" already exists.");
            return;
        }
        stories.put(title, new Story(title, opening));
        System.out.println(username + " created new story: " + title);
        send("INFO Story created: " + title);
        // Broadcast the refreshed story list to every connected client.
        StoryServer.broadcastStoryList();
    }

    // Client wants to leave the current story and return to the lobby.
    private void handleLeave() {
        if (currentStory == null) return;
        Story leaving = currentStory;
        currentStory = null;
        leaving.removeClient(this);
        System.out.println(username + " left story: " + leaving.getTitle());
        // If it was this client's turn, advance to the next writer.
        promptNext(leaving);
        // Send the client a fresh story list so they can pick again.
        sendStoryList();
        send("BACK_TO_LOBBY");
    }

    // Tell the next client in line that it's their turn.
    private void promptNext(Story story) {
        if (story.isFinished()) return;
        ClientHandler next = story.nextClient();
        if (next != null) {
            next.send("YOUR_TURN");
        }
    }
}