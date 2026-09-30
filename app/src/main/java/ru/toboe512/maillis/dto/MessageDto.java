package ru.toboe512.maillis.dto;


public class MessageDto {
    private final int id; // ДОБАВЛЕНО ПОЛЕ ID
    private final String senderEmail;
    private final String subject;

    public void setText(String text) {
        this.text = text;
    }

    private String text;
    private final long timestamp;
    private final boolean isMine; // Флаг: отправлено мной или нет
    private final String serverUid; // ДОБАВЛЕНО ПОЛЕ ДЛЯ СЕРВЕРНОГО ID
    private final int status;

    private String messageType; // "TEXT" или "IMAGE"
    private String filePath;    // Путь к картинке во внутреннем кэше

    public MessageDto(int id, String senderEmail, String subject, String text, long timestamp, boolean isMine, String serverUid, int status, String messageType) {
        this.id = id;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.text = text;
        this.timestamp = timestamp;
        this.isMine = isMine;
        this.serverUid = serverUid;
        this.status = status;
        this.messageType = messageType;
    }

    public MessageDto(int id, String senderEmail, String subject, String text, long timestamp, boolean isMine, String serverUid, int status, String messageType, String filePath) {
        this.id = id;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.text = text;
        this.timestamp = timestamp;
        this.isMine = isMine;
        this.serverUid = serverUid;
        this.status = status;
        this.messageType = messageType;
        this.filePath = filePath;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getStatus() {
        return status;
    }

    public String getSubject() {
        return subject;
    }

    public String getServerUid() {
        return serverUid;
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