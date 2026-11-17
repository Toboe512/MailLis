package ru.toboe512.maillis.dto;

import java.util.Map;
import java.util.Objects;

public class NetworkMessageDto {

    private String id;

    private String content;

    private String from;

    private String to;

    private String subject;

    private long timestamp;

    private Map<String, String> headers;


    public NetworkMessageDto(String id, String content, String from, String subject, long timestamp, Map<String, String> headers) {
        this.id = id;
        this.content = content;
        this.from = from;
        this.subject = subject;
        this.timestamp = timestamp;
        this.headers = headers;
    }

    public NetworkMessageDto(String content, String to, String subject, Map<String, String> headers) {
        this.content = content;
        this.to = to;
        this.subject = subject;
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
        NetworkMessageDto that = (NetworkMessageDto) o;
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

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }
}
