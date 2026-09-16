package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public interface DBService {
    List<Message> getMessages(long chatId, String myEmail);

    int saveMessage(long chatId, String myEmail, String subject, String text, long timestamp);

    List<ChatPreview> loadChats();

    long saveChat(String title, String targetEmail);

    boolean saveSharedSecretKey(String email, byte[] sharedSecretKeyBytes);

    List<Contact> getContacts();

    int deleteContactFromDb(int contactId);

    ChatDbHelper getDbHelper();

    long getChatIdByRecipientEmail(String email);

    int deleteMassage(int msdId);


    boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp, String serverUid);

    void updateServerUid(String generatedServerUid, int localMessageId);

    String getRecipientEmailByChatId(long chatId);

    byte[] getSharedSecret(long chatId);
}
