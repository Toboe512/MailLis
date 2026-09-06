package ru.toboe512.maillis.dto;


public class ChatPreview {
    private final int id;
    private final String title;
    private final String type; // "PERSONAL" или "GROUP"
    private final String lastMessage;

    public ChatPreview(int id, String title, String type, String lastMessage) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.lastMessage = lastMessage;
    }

    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getType() { return type; }
    public String getLastMessage() { return lastMessage; }
}
