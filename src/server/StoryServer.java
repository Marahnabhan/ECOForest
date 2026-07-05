package server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * StoryServer - Multithreaded server for the Collaborative Story Writing app.
 *
 * Responsibilities:
 *  - Accept multiple client connections (one thread per client).
 *  - Keep a list of stories (title -> current text).
 *  - When a client joins, send the list of available stories.
 *  - When the client picks a story, put the client in that story's turn queue.
 *  - Round-robin: only one client at a time gets a YOUR_TURN prompt.
 *  - After the client sends a word, append it to the story and broadcast
 *    the updated version to everyone in that story.
 *  - Then prompt the next client in the queue.
 */
public class StoryServer {

    public static final int PORT = 6500;

    // All stories on the server. Key = story title, Value = Story object.
    private static final Map<String, Story> stories = new ConcurrentHashMap<>();

    // All currently connected handlers (for broadcasting the story list).
    private static final List<ClientHandler> allHandlers =
            Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        // Seed a few forest-themed stories so clients have something to pick.
        stories.put("The Lost Trail", new Story("The Lost Trail",
                "The path through the pines had not been walked in years."));
        stories.put("Whispers of the Grove", new Story("Whispers of the Grove",
                "At dawn the old oak began to speak in a voice like rustling leaves."));
        stories.put("Guardians of the Canopy", new Story("Guardians of the Canopy",
                "High above the forest floor, the watchers stirred in their nests."));

        System.out.println("ECOForest Server started on port " + PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());
                // Each client gets its own thread.
                ClientHandler handler = new ClientHandler(clientSocket);
                allHandlers.add(handler);
                Thread t = new Thread(handler);
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }

    // Give other classes access to the stories map.
    public static Map<String, Story> getStories() {
        return stories;
    }

    // Remove a handler when its client disconnects.
    public static void removeHandler(ClientHandler handler) {
        allHandlers.remove(handler);
    }

    // Send every connected client a fresh STORIES list (called after a new story is created).
    public static void broadcastStoryList() {
        StringBuilder sb = new StringBuilder("STORIES ");
        boolean first = true;
        for (String title : stories.keySet()) {
            if (!first) sb.append("|");
            sb.append(title);
            first = false;
        }
        String msg = sb.toString();
        synchronized (allHandlers) {
            for (ClientHandler h : allHandlers) {
                h.send("REFRESH_STORIES " + msg.substring(8)); // strip "STORIES "
            }
        }
    }
}
