package ru.toboe512.maillis.dto;

import java.util.Map;
import java.util.Objects;

public class IncomingMessageDto {

    private String id;

    private String content;

    private String from;

    private String subject;

    private long timestamp;

    private Map<String, String> headers;


    public IncomingMessageDto(String id, String content, String from, String subject, long timestamp, Map<String, String> headers) {
        this.id = id;
        this.content = content;
        this.from = from;
        this.subject = subject;
        this.timestamp = timestamp;
        this.headers = headers;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IncomingMessageDto that = (IncomingMessageDto) o;
        return timestamp == that.timestamp &&
                Objects.equals(id, that.id) &&
                Objects.equals(content, that.content) &&
                Objects.equals(from, that.from) &&
                Objects.equals(subject, that.subject) &&
                Objects.equals(headers, that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, content, from, subject, timestamp, headers);
    }
}
