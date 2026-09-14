package ru.toboe512.maillis.dto;


public class Message {
    private final int id; // ДОБАВЛЕНО ПОЛЕ ID
    private final String senderEmail;

    public String getSubject() {
        return subject;
    }

    private final String subject;
    private final String text;
    private final long timestamp;
    private final boolean isMine; // Флаг: отправлено мной или нет

    public String getServerUid() {
        return serverUid;
    }

    private final String serverUid; // ДОБАВЛЕНО ПОЛЕ ДЛЯ СЕРВЕРНОГО ID

    public Message(int id, String senderEmail, String subject, String text, long timestamp, boolean isMine, String serverUid) {
        this.id = id;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.text = text;
        this.timestamp = timestamp;
        this.isMine = isMine;
        this.serverUid = serverUid;
    }

    public int getId() {
        return id;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isMine() {
        return isMine;
    }
}