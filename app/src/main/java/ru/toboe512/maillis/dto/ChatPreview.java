package ru.toboe512.maillis.dto;


public class ChatPreview {

    private final int id;
    private String title;
    private final String type; // "PERSONAL" или "GROUP"
    private final boolean isHandShake;

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    private String lastMessage;

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isHandShake() {
        return isHandShake;
    }

    public ChatPreview(int id, String title, String type, String lastMessage, boolean isHandShake) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.lastMessage = lastMessage;
        this.isHandShake = isHandShake;
    }

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getType() {
        return type;
    }

    public String getLastMessage() {
        return lastMessage;
    }
}
