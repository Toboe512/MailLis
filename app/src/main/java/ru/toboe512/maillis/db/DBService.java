package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public interface DBService {
    List<Message> getAllMessages(long chatId, String myEmail);

    List<Message> getMessages(long chatId, String myEmail);

    Message getMessage(long localMessageId);

    int saveMessage(long chatId, String myEmail, String subject, String text, boolean isMine, long timestamp);

    void markMessageAsSent(int localMessageId, String serverUid);

    List<ChatPreview> loadChats();

    long saveChat(String title, String targetEmail);

    boolean saveSharedSecretKey(String email, byte[] sharedSecretKeyBytes);

    List<Contact> getContacts();

    int deleteContact(int contactId);

    int deleteContact(String email);

    long getChatIdByRecipientEmail(String email);

    int deleteMessage(int msgId);


    boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp, String serverUid);

    void updateServerUid(String generatedServerUid, int localMessageId);

    String getRecipientEmailByChatId(long chatId);

    byte[] getSevedPublicKey(long chatId);

    List<Message> getUnsentMessages();

    int getChatIdByMessageId(int messageId);

    boolean banUser(String email);

    boolean unbanUser(String email);

    boolean isUserBanned(String email);
    List<String> getBannedUsers();

    boolean deleteChatAndMessages(long chatId);

    void markMessageAsRead(String serverUid);

    void markMessageAsSending(int localMessageId);

    void resetMessageStatusToUnsent(int localMessageId);

    int getTotalMessagesCount();

    Message getLastIncomingMessage();

}
