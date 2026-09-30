package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.MessageDto;

public interface DBService {
    /**
     * Извлекает полную историю переписки для конкретного экрана ChatActivity.
     * Полностью адаптирован под раздельную структуру таблиц одиночных чатов и групп.
     * Сортирует сообщения от старых к новым для правильного отображения в RecyclerView.
     *
     * @param chatId  локальный идентификатор комнаты (из таблицы chats или group_chats)
     * @param isGroup флаг типа чата: true - если это группа, false - если одиночный Тет-а-Тет
     * @return список объектов Message, готовый для передачи в DiffUtil и адаптер
     */
    List<MessageDto> getAllMessages(long chatId, boolean isGroup);

    MessageDto getMessage(long localMessageId);

    /**
     * Безопасно сохраняет сообщение (входящее или исходящее) в таблицу messages.
     * Автоматически определяет тип комнаты (одиночный чат или группа) и выставляет флаги.
     * Полностью очищен от обращения к удаленным колонкам таблицы chats.
     *
     * @param chatId    локальный числовой идентификатор чата или группы
     * @param myEmail   email отправителя сообщения (свой или собеседника)
     * @param subject   тема письма (служебный тег MailLis)
     * @param text      чистый текст сообщения
     * @param isMine    флаг авторства: true - если сообщение отправили вы, false - если оно входящее
     * @param timestamp время создания сообщения
     * @return сгенерированный базой данных уникальный локальный ID сообщения
     */
    int saveGenericMessage(long chatId, String myEmail, String subject, String text, boolean isMine, long timestamp);

    /**
     * Сохраняет исходящее (собственное) сообщение в SQLite базу данных.
     * Автоматически определяет контекст (одиночный чат или группа) и выставляет флаги.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId           локальный числовой идентификатор чата (из chats или group_chats)
     * @param senderEmail      email текущего пользователя приложения (myEmail)
     * @param subject          тема письма (служебный тег MailLis, например MAILLIS_CHAT_MESSAGE)
     * @param messageText      чистый, еще не зашифрованный текст сообщения для локального отображения
     * @param currentTimestamp время создания сообщения на устройстве
     * @return сгенерированный базой данных уникальный ID строки (id сообщения) для сетевого слоя ретрая
     */
    int saveMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp);

    void markMessageAsSent(int localMessageId, String serverUid);

    List<ChatPreview> loadChats();

    long saveChat(String title, String targetEmail);

    void savePublicKey(String email, byte[] sharedSecretKeyBytes);

    List<Contact> getContacts();

    int deleteContact(int contactId);

    int deleteContact(String email);

    long getChatIdByRecipientEmail(String email);

    int deleteMessage(int msgId);

    boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp, String serverUid, String type, String imgPath);

    void updateServerUid(String generatedServerUid, int localMessageId);

    String getRecipientEmailByChatId(long chatId);

    /**
     * Извлекает публичный Tink Protobuf-ключ собеседника по локальному ID одиночного чата.
     * Использует реляционное объединение JOIN с изолированной таблицей-кэшем public_keys.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId локальный числовой идентификатор одиночного чата из таблицы chats
     * @return бинарный массив публичного ключа Tink, либо null, если ключ еще не был получен
     */
    byte[] getPublicKey(long chatId);

    byte[] getPublicKeyByEmail(String email);

    List<MessageDto> getUnsentMessages();

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

    MessageDto getLastIncomingMessage();

    void updateMessageTypeAndPath(int messageId, String type, String path);

    /**
     * Создает локальную карточку нового группового чата (для Создателя группы)
     * Записывает данные строго в новую изолированную таблицу 'group_chats'.
     * Автоматически синхронизирует локальный ID с полем remote_group_id.
     *
     * @param groupName         текстовое название создаваемой группы
     * @param generatedGroupKey сгенерированный 32-байтный симметричный секретный AES-ключ группы
     * @param creatorEmail      email текущего пользователя (создателя группы)
     * @return уникальный локальный ID созданной группы, либо -1 при ошибке
     */
    long createGroupChat(String groupName, byte[] generatedGroupKey, String creatorEmail);
    /**
     * Добавляет участника в групповой чат.
     */
    void addGroupMember(long chatId, String userEmail);

    /**
     * Возвращает список всех Email-адресов участников группы (кроме своего).
     */
    List<String> getGroupMembers(long chatId, String myEmail);

    /**
     * Проверяет, является ли указанный идентификатор чата групповой комнатой.
     * Ищет строго в новой независимой таблице 'group_chats'.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId локальный числовой идентификатор чата или группы
     * @return true, если чат групповой; false, если чат одиночный (Тет-а-Тет)
     */
    boolean isGroupChat(long chatId);

    byte[] getGroupSecretKey(long chatId);

    void saveReceivedGroupChat(long remoteChatId, String incomingGroupName, byte[] decryptedGroupKey, String creatorEmail);

    /**
     * Безвозвратно удаляет публичный ключ Tink собеседника из глобального кэша базы данных.
     * Вызывается сразу после успешного удаления одиночного или группового чата для очистки крипто-следов.
     *
     * @param email почтовый адрес пользователя, чей ключ необходимо стереть
     * @return true, если строка с ключом была успешно найдена и удалена; false в противном случае
     */
    boolean deletePublicKeyByEmail(String email);

    /**
     * Полностью и безвозвратно удаляет групповой чат, список его участников и историю переписки.
     * Использует прямые SQL-запросы для исключения багов приведения типов на Android 14+.
     *
     * @param localGroupId локальный числовой ID группы из таблицы group_chats
     * @return true, если транзакция удаления завершилась успешно; false в случае сбоя
     */
    boolean deleteGroupChatAndMessages(long localGroupId);
}
