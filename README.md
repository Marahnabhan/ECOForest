# 🌲 ECOForest

A turn-based collaborative story-writing application with a woodland theme. Multiple players join a shared story and take turns adding sentences, watching the tale unfold live as a multithreaded Java server coordinates whose turn it is.

## Overview

ECOForest was built for a Java Networking course project. Players connect to a central server, choose an existing story (or create a new one), and contribute one sentence at a time in a round-robin format. Every player sees the story update in real time as turns are taken, until the story reaches its turn limit and is complete.

## Features

- **Round-robin collaborative writing** — players take turns contributing one sentence at a time (up to 12 turns per story); everyone sees the story update live as each turn is submitted.
- **Multiple concurrent stories** — players can join one of several forest-themed stories already in progress, or create a brand new one with its own title and opening sentence.
- **Multithreaded server** — built on Java sockets, with each connected client handled on its own dedicated thread and shared story state synchronized for safe concurrent access.
- **Custom Swing GUI** — a themed client interface (forest color palette, custom story-picker dialog, and a painted tree icon) with a background reader thread that keeps the UI responsive.
- **Lobby flow** — players can finish a story and return to the lobby to pick or create another one without restarting the client.

## Tech Stack

- **Language:** Java
- **GUI:** Java Swing
- **Networking:** Java Sockets (`DataInputStream`/`DataOutputStream`), one thread per client
- **Architecture:** Client-server, with a simple text-based message protocol over the socket connection

## Project Structure

- `server/StoryServer.java` — entry point; opens the server socket on port `6500`, seeds initial stories, and spins up a thread per connecting client.
- `server/ClientHandler.java` — handles all communication with one connected client on its own thread.
- `server/Story.java` — represents a single story: its text, turn count, and the round-robin queue of players.
- `client/StoryClientGui.java` — entry point for the Swing client; connects to the server, shows the story picker, and runs the main writing UI.

## How to Run

1. Clone the repository:
   ```
   git clone https://github.com/marahnabhan/ECOForest.git
   ```
2. Make sure `StoryServer.java` and `ClientHandler.java` and `Story.java` are inside a `server/` folder, and `StoryClientGui.java` is inside a `client/` folder (matching their package declarations).
3. Compile everything from the project root:
   ```
   javac server/*.java client/*.java
   ```
4. Start the server:
   ```
   java server.StoryServer
   ```
5. Start one or more clients (in separate terminals):
   ```
   java client.StoryClientGui
   ```
6. Enter a username, then pick an existing story from the dropdown or create a new one. Take turns adding sentences until the story reaches its 12-turn limit.

## Team

Developed by Marah Abu Nabhan (lead), Hamzeh, and Mohammad, as part of a Java Networking course project.

## Screenshots

*(Add a screenshot or GIF of the story picker and writing screen here to give visitors a quick preview.)*
