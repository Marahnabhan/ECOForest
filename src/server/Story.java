package server;

import java.util.*;

/**
 * Story - represents a single collaborative story.
 *
 * Holds the current text and the queue of clients waiting for their turn.
 * Methods are synchronized so two threads can't update at the same time.
 */
public class Story {

    public static final int MAX_TURNS = 12;

    private final String title;
    private StringBuilder text;
    private int turnsTaken = 0;
    // Round-robin queue of connected clients for this story.
    private final Queue<ClientHandler> turnQueue = new LinkedList<>();

    public Story(String title, String startingText) {
        this.title = title;
        this.text = new StringBuilder(startingText);
    }

    public String getTitle() {
        return title;
    }

    public synchronized String getText() {
        return text.toString();
    }

    public synchronized int getTurnsTaken() {
        return turnsTaken;
    }

    public synchronized boolean isFinished() {
        return turnsTaken >= MAX_TURNS;
    }

    // Append a sentence contributed by a client to the story.
    public synchronized void addSentence(String sentence) {
        text.append(" ").append(sentence);
        turnsTaken++;
    }

    // Add a client to the back of the queue.
    public synchronized void addClient(ClientHandler client) {
        turnQueue.offer(client);
    }

    // Remove a client (used when they disconnect).
    public synchronized void removeClient(ClientHandler client) {
        turnQueue.remove(client);
    }

    // Get next client whose turn it is (rotates the queue).
    public synchronized ClientHandler nextClient() {
        if (turnQueue.isEmpty()) return null;
        ClientHandler next = turnQueue.poll(); // remove from front
        turnQueue.offer(next);                 // add to back again
        return next;
    }

    // Peek at the client whose turn it currently is (front of queue).
    public synchronized ClientHandler currentClient() {
        return turnQueue.peek();
    }

    // Get all clients in this story (for broadcasting).
    public synchronized List<ClientHandler> getAllClients() {
        return new ArrayList<>(turnQueue);
    }

    public synchronized int size() {
        return turnQueue.size();
    }
}
