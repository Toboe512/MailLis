package ru.toboe512.maillis.dto;


public class ChatPreview {

    private final long id;
    private String title;
    private final boolean isHandShake;

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    private String lastMessage;

    public boolean isGroup() {
        return isGroup;
    }

    private final boolean isGroup;     // ФЛАГ: true - группа, false - Тет-а-Тет

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isHandShake() {
        return isHandShake;
    }

    public ChatPreview(long id, String title, String lastMessage, boolean isHandShake, boolean isGroup) {
        this.id = id;
        this.title = title;
        this.lastMessage = lastMessage;
        this.isHandShake = isHandShake;
        this.isGroup = isGroup;
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getLastMessage() {
        return lastMessage;
    }
}
