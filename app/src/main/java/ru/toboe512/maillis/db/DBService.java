package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.MessageDto;

public interface DBService {


    /**
     * Метод нативного чтения строк из таблицы SQLite
     *
     * @return писок строк контактов
     */
    List<Contact> getContacts();

    /**
     * Нативное удаление контакта из SQLite по ее ID
     *
     * @param contactId идентификатор контакта
     * @return колличество удиённых контактов
     */
    int deleteContact(int contactId);

    /**
     * Нативное удаление контакта из SQLite по ее email
     * @param email почта
     * @return колличество удиённых контактов
     */
    int deleteContact(String email);

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

    /**
     * Находит и возвращает конкретное сообщение из таблицы messages по его уникальному ID.
     *
     * @param localMessageId уникальный локальный идентификатор сообщения (id)
     * @return объект Message с заполненными данными или null, если сообщение не найдено
     */
    MessageDto getMessage(long localMessageId);

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
     * Безопасно сохраняет входящее сообщение (текст или медиа) в SQLite базу данных.
     * Гарантированно поддерживает одиночные чаты (Тет-а-Тет) и группы, автоматически выставляя флаги.
     * Защищено от дубликатов писем с помощью механизма CONFLICT_IGNORE.
     *
     * @param chatId           локальный идентификатор чата (из chats или group_chats)
     * @param senderEmail      email отправителя сообщения
     * @param subject          тема письма (служебный тег MailLis)
     * @param messageText      чистый расшифрованный текст сообщения (или заглушка "[Изображение]")
     * @param currentTimestamp время отправки сообщения сервером
     * @param serverUid        уникальный Message-ID, присвоенный сервером Mail.ru
     * @param type             тип контента: "TEXT" или "IMAGE"
     * @param imgPath          абсолютный путь к расшифрованному файлу .jpg на диске телефона (или null)
     * @return true, если сообщение успешно записано в базу; false, если произошел сбой или дубликат
     */
    boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp, String serverUid, String type, String imgPath);

    /**
     * Извлекает из SQLite самое последнее сообщение для конкретного чата или группы.
     * Защищено от багов приведения типов (Type Mismatch) и смешивания контекстов.
     *
     * @param chatId  локальный идентификатор чата или группы
     * @param isGroup флаг типа комнаты: true - группа, false - одиночный чат
     * @return объект Message со всеми заполненными данными, либо null, если история пуста
     */
    MessageDto getLastMessage(long chatId, boolean isGroup);

    int deleteMessage(int msgId);

    /**
     * Пометка сообщения как успешно отправленного
     * @param localMessageId идентификатор сообщения
     * @param serverUid серверный идентификатор
     */
    void markMessageAsSent(int localMessageId, String serverUid);

    /**
     * Метод чтения активных чатов из SQLite
     * @return возвращает список превью
     */
    List<ChatPreview> loadChats();

    /**
     * Сохраняет новый одиночный чат (Тет-а-Тет) в таблицу 'chats'.
     * Автоматически защищает от дублирования комнат с одним и тем же собеседником.
     *
     * @param title       отображаемое имя чата (обычно совпадает с email)
     * @param targetEmail почтовый адрес собеседника
     * @return уникальный ID чата (созданного или уже существующего), либо -1 в случае критической ошибки
     */
    long saveChat(String title, String targetEmail);

    /**
     * Находит и возвращает ID личного чата (Тет-а-Тет) по Email-адресу собеседника.
     * Ищет строго в таблице 'chats'. Защищено от багов разного регистра букв (Case-Insensitive).
     *
     * @param email почтовый адрес собеседника
     * @return уникальный ID чата из таблицы chats, либо -1, если чат еще не создан
     */
    long getChatIdByRecipientEmail(String email);

    /**
     * Сохраняет или обновляет публичный ключ Tink для конкретного Email.
     * Работает как глобальный изолированный кэш ключей мессенджера.
     *
     * @param email          почта пользователя
     * @param publicKeyBytes публичный ключ собеседника
     */
    void savePublicKey(String email, byte[] publicKeyBytes);

    /**
     * Извлекает публичный Tink Protobuf-ключ собеседника по локальному ID одиночного чата.
     * Использует реляционное объединение JOIN с изолированной таблицей-кэшем public_keys.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId локальный числовой идентификатор одиночного чата из таблицы chats
     * @return бинарный массив публичного ключа Tink, либо null, если ключ еще не был получен
     */
    byte[] getPublicKey(long chatId);

    /**
     * Извлекает публичный ключ Tink из глобального кэша для любого Email.
     * Подходит как для одиночных чатов, так и для веерной рассылки в группах.
     *
     * @param email почта собеседника
     * @return ключ в массиве байт
     */
    byte[] getPublicKeyByEmail(String email);

    void updateServerUid(String generatedServerUid, int localMessageId);

    /**
     * Находит и возвращает Email собеседника (получателя) по уникальному ID одиночного чата.
     * Ищет строго в таблице 'chats' с защитой от багов приведения типов на Android 14+.
     *
     * @param chatId уникальный локальный числовой идентификатор чата из таблицы chats
     * @return email адрес собеседника или пустая строка (EMPTI_STRING), если чат не найден
     */
    String getRecipientEmailByChatId(long chatId);

    /**
     * Помечает сообщение как не отправленное
     * @return список всех сообщений
     */
    List<MessageDto> getUnsentMessages();

    int getChatIdByMessageId(int messageId);

    /**
     * Добавить пользователя в бан-лист
     * @param email почта пользователя
     * @return флаг успешности
     */
    boolean banUser(String email);

    /**
     * Удалить пользователя из бан-листа (разбанить)
     * @param email почта пользователя
     * @return флаг успешности
     */
    boolean unbanUser(String email);

    /**
     * Проверка, находится ли пользователь в бане
     * @param email почта пользователя
     * @return флаг бана
     */
    boolean isUserBanned(String email);

    List<String> getBannedUsers();

    /**
     * Полностью и безвозвратно удаляет чат и всю историю сообщений из SQLite базы данных.
     * Защищено от багов приведения типов (Type Mismatch) в современных Android SDK.
     *
     * @param chatId уникальный идентификатор удаляемого чата
     * @return true, если чат был успешно стерт из таблицы chats
     */
    boolean deleteChatAndMessages(long chatId);

    /**
     * Переводит статус сообщения в состояние "Прочитано" (две галочки).
     *
     * @param serverUid серверный идентификатор
     */
    void markMessageAsRead(String serverUid);

    /**
     * Метод перевода сообщения в статус отправки (защита от повторной отправки параллельным потоком)
     *
     * @param localMessageId идентификатор сообщения
     */
    void markMessageAsSending(int localMessageId);

    void resetMessageStatusToUnsent(int localMessageId);

    /**
     * Моментально возвращает общее количество сообщений, сохраненных в таблице messages.
     * Используется фоновой службой MailLisPushService для отслеживания появления новых входящих писем.
     *
     * @return общее число строк в таблице messages или 0 в случае пустоты или сбоя
     */
    int getTotalMessagesCount();

    /**
     * Извлекает из SQLite самое последнее прилетевшее входящее сообщение (is_mine = 0).
     * Используется фоновой службой MailLisPushService для формирования текста всплывающего Push-уведомления.
     *
     * @return объект Message с заполненными данными отправителя и текста, либо null, если входящих сообщений еще нет
     */
    MessageDto getLastIncomingMessage();

    /**
     * Обновляет тип сообщения и путь к локальному медиафайлу в SQLite.
     *
     * @param messageId идентификатор сообщения
     * @param type      тип сообщения картинка или текст
     * @param path      путь к файлу картинка
     */
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
     * Безопасно добавляет нового участника в групповой чат.
     * Полностью адаптирован под актуальную колонку 'group_id'.
     * Защищен от дублирования записей внутри одной и той же группы.
     *
     * @param chatId    числовой ID группы из таблицы group_chats
     * @param userEmail email-адрес добавляемого участника
     */
    void addGroupMember(long chatId, String userEmail);

    /**
     * Возвращает список Email-адресов всех участников конкретной группы (исключая свой собственный).
     * Полностью адаптирован под новую структуру таблицы group_members (колонка group_id).
     *
     * @param localGroupId локальный числовой ID группы из таблицы group_chats
     * @param myEmail      email текущего пользователя приложения (чтобы исключить себя из рассылки)
     * @return список email-адресов участников группы
     */
    List<String> getGroupMembers(long localGroupId, String myEmail);

    /**
     * Проверяет, является ли указанный идентификатор чата групповой комнатой.
     * Ищет строго в новой независимой таблице 'group_chats'.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId локальный числовой идентификатор чата или группы
     * @return true, если чат групповой; false, если чат одиночный (Тет-а-Тет)
     */
    boolean isGroupChat(long chatId);

    /**
     * Извлекает секретный AES-ключ группы по её локальному ID.
     *
     * @param chatId идентификатор группы
     * @return массив байт группового ключа
     */
    byte[] getGroupSecretKey(long chatId);

    /**
     * Безопасно сохраняет или обновляет информацию о групповом чате, созданном другим пользователем,
     * записывая данные строго в новую изолированную таблицу group_chats.
     * Защищено от дублирования комнат при повторном сканировании писем по IMAP.
     *
     * @param remoteChatId      уникальный глобальный ID чата, сгенерированный создателем группы
     * @param incomingGroupName текстовое название группы, пришедшее из инвайта
     * @param decryptedGroupKey расшифрованный 32-байтный секретный AES-ключ этой группы (или null, если на этапе Pre-Invite)
     * @param creatorEmail      email создателя группы (тот, кто прислал инвайт)
     */
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
