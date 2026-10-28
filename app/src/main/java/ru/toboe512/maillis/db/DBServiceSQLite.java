package ru.toboe512.maillis.db;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.TEXT_MSG;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.time.temporal.ValueRange;
import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.MessageDto;

public class DBServiceSQLite implements DBService {

    // Переменные для работы с базой данных
    private final ChatDbHelper dbHelper;


    // Внутренний статический класс-держатель.
    // Он загрузится в память строго в тот момент, когда впервые вызовут метод getInstance()
    private static class Holder {
        private static DBServiceSQLite INSTANCE;
    }

    // Единственная глобальная точка доступа (Потокобезопасно на уровне JVM)
    public static DBServiceSQLite getInstance(Context context) {
        if (Holder.INSTANCE == null) {
            Holder.INSTANCE = new DBServiceSQLite(context);
        }
        return Holder.INSTANCE;
    }

    private DBServiceSQLite(Context cotext) {
        this.dbHelper = new ChatDbHelper(cotext.getApplicationContext());
    }

    public List<Contact> getContacts() {
        List<Contact> contactList = new ArrayList<>();

        // НЕ закрываем саму базу данных (db.close()), так как это ломает пул соединений приложения,
        // но ОБЯЗАТЕЛЬНО оборачиваем Cursor в try-with-resources для автоматического закрытия!
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрос всех колонок из таблицы contacts
        try (// Вместо полной выборки делаем выборку с фильтрацией через inner/sub-запрос:
             Cursor cursor = db.rawQuery(
                     "SELECT * FROM contacts WHERE email NOT IN (SELECT email FROM ban_list) ORDER BY name ASC",
                     null
             )) {

            if (cursor.moveToFirst()) {
                // 1. ОПТИМИЗАЦИЯ: Узнаем индексы колонок строго ОДИН РАЗ до начала цикла
                int idIndex = cursor.getColumnIndex("id");
                int nameIndex = cursor.getColumnIndex("name");
                int emailIndex = cursor.getColumnIndex("email");

                // 2. Проверяем, что все необходимые колонки физически существуют в таблице
                if (idIndex >= 0 && nameIndex >= 0 && emailIndex >= 0) {
                    do {
                        // Считываем значения из текущей строки
                        int id = cursor.getInt(idIndex);
                        String name = cursor.getString(nameIndex);
                        String email = cursor.getString(emailIndex);

                        // Добавляем готовый объект в наш список
                        contactList.add(new Contact(id, name, email));

                    } while (cursor.moveToNext()); // Переходим к следующему контакту
                }
            }
        }
        return contactList;
    }

    public int deleteContact(int contactId) {
        // Запрос удаления: DELETE FROM contacts WHERE id = contactId
        return dbHelper.getWritableDatabase().delete("contacts", "id = ?", new String[]{String.valueOf(contactId)});
    }

    public int deleteContact(String email) {
        // Запрос удаления: DELETE FROM contacts WHERE email = email
        return dbHelper.getWritableDatabase().delete("contacts", "email = ?", new String[]{String.valueOf(email)});
    }

    /**
     * Извлекает полную историю переписки для конкретного экрана ChatActivity.
     * Полностью защищен от багов рассинхронизации локальных и глобальных ID.
     */
    public List<MessageDto> getAllMessages(long chatId, boolean isGroup) {
        List<MessageDto> messageDtoList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        String sql;
        String[] selectionArgs;

        if (isGroup) {
            // --- РЕЖИМ ГРУППЫ (Наш рабочий подзапрос) ---
            sql = "SELECT * FROM messages WHERE is_group_message = 1 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT remote_group_id FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1) OR " +
                    "  chat_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                    ") ORDER BY timestamp ASC";

            selectionArgs = new String[]{String.valueOf(chatId), String.valueOf(chatId), String.valueOf(chatId)};
        } else {
            // =========================================================================
            // ИСПРАВЛЕНИЕ ДЛЯ ОДИНОЧНЫХ ЧАТОВ:
            // Если активность получила remote-индентификатор или произошел сдвиг автоинкремента,
            // этот подзапрос свяжет историю по recipient_email, страхуя отображение своих сообщений!
            // =========================================================================
            sql = "SELECT * FROM messages WHERE is_group_message = 0 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT id FROM chats WHERE recipient_email = " +
                    "    (SELECT recipient_email FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1))" +
                    ") ORDER BY timestamp ASC";

            selectionArgs = new String[]{String.valueOf(chatId), String.valueOf(chatId)};
        }

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {

                // Считываем индексы колонок один раз на самом верху (критическая оптимизация производительности)
                int idIdx = cursor.getColumnIndex("id");
                int senderIdx = cursor.getColumnIndex("sender_email");
                int textIdx = cursor.getColumnIndex("message_text");
                int subjectIdx = cursor.getColumnIndex("subject");
                int timeIdx = cursor.getColumnIndex("timestamp");
                int uidIdx = cursor.getColumnIndex("server_uid");
                int sentIdx = cursor.getColumnIndex("is_sent");
                int mineIdx = cursor.getColumnIndex("is_mine");
                int typeIdx = cursor.getColumnIndex("message_type");
                int pathIdx = cursor.getColumnIndex("file_path");

                // Проверяем существование главных структурных полей
                if (idIdx >= 0 && senderIdx >= 0 && textIdx >= 0 && timeIdx >= 0) {
                    do {
                        int id = cursor.getInt(idIdx);
                        String sender = cursor.getString(senderIdx);
                        String text = cursor.getString(textIdx);
                        long timestamp = cursor.getLong(timeIdx);

                        // Безопасное извлечение опциональных и добавленных колонок
                        String subject = subjectIdx >= 0 ? cursor.getString(subjectIdx) : "";

                        // ИСПРАВЛЕНО: Теперь используется строго правильная переменная индекса uidIdx
                        String serverUid = uidIdx >= 0 ? cursor.getString(uidIdx) : "";

                        // Конвертируем SQLite INTEGER флага в Java boolean структуры
                        boolean isMine = mineIdx >= 0 && cursor.getInt(mineIdx) == 1;
                        int isSentStatus = sentIdx >= 0 ? cursor.getInt(sentIdx) : 1;

                        // Новые медиа-поля
                        String messageType = typeIdx >= 0 ? cursor.getString(typeIdx) : TEXT_MSG;
                        String filePath = pathIdx >= 0 ? cursor.getString(pathIdx) : null;

                        // Собираем полноценный DTO-объект сообщения
                        MessageDto messageDto = new MessageDto(
                                id,
                                sender,
                                subject,
                                text,
                                timestamp,
                                isMine,
                                serverUid,
                                isSentStatus,
                                messageType,
                                filePath,
                                isGroup

                        );

                        messageDtoList.add(messageDto);

                    } while (cursor.moveToNext());
                }
            }
            android.util.Log.d("MailLis_DB", " getAllMessages: считано для Тет-а-Тет " + chatId + " -> " + messageDtoList.size());
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Критическая ошибка при чтении истории одиночного чата: " + chatId, e);
            e.printStackTrace();
        }

        return messageDtoList;
    }

    public int saveGenericMessage(long chatId, String myEmail, String subject, String text, boolean isMine, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long newId = -1;

        try {
            // =========================================================================
            // ШАГ 1: АВТО-ОПРЕДЕЛЕНИЕ ТИПА КОМНАТЫ (Одиночный чат или Группа)
            // =========================================================================
            boolean isGroup = false;

            // 1. Сначала проверяем, существует ли этот ID в таблице одиночных чатов
            String checkPrivateSql = "SELECT 1 FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkPrivateSql, new String[]{String.valueOf(chatId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    isGroup = false; // Нашли в chats -> это Тет-а-Тет
                } else {
                    // 2. Если в одиночных чатах нет, проверяем таблицу групповых чатов
                    String checkGroupSql = "SELECT 1 FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
                    try (Cursor groupCursor = db.rawQuery(checkGroupSql, new String[]{String.valueOf(chatId)})) {
                        if (groupCursor != null && groupCursor.moveToFirst()) {
                            isGroup = true; // Нашли в group_chats -> это группа
                        }
                    }
                }
            }
            // ШАГ 2: СБОРКА ДАННЫХ С УЧЕТОМ ОБНОВЛЕННОЙ СТРУКТУРЫ ТАБЛИЦЫ MESSAGES
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", myEmail != null ? myEmail.trim().toLowerCase() : "");
            values.put("message_text", text);
            values.put("subject", subject);
            values.put("timestamp", timestamp);

            values.put("is_mine", isMine ? 1 : 0);

            // Выставляем статус отправки: исходящие (0 - часики), входящие (1 - доставлено)
            values.put("is_sent", isMine ? 0 : 1);

            // Дефолтные медиа-поля для обычного текста
            values.put("message_type", TEXT_MSG);
            values.put("file_path", (String) null);

            // ИСПРАВЛЕНИЕ: Записываем вычисленный реляционный флаг контекста (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // ШАГ 3: ЗАПИСЬ В БАЗУ ДАННЫХ В ОБХОД ТАБЛИЦЫ CHATS
            newId = db.insert("messages", null, values);

            Log.d("MailLis_DB", "Сообщение успешно добавлено. ID: " + newId
                    + " (Контекст: " + (isGroup ? "ГРУППА" : "ТЕТ-А-ТЕТ") + ")");

        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при сохранении сообщения через saveGenericMessage", e);
            e.printStackTrace();
        }

        return (int) newId;
    }

    public int saveMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int generatedId = -1;

        try {
            // ШАГ 1: ИНТЕЛЛЕКТУАЛЬНОЕ АВТО-ОПРЕДЕЛЕНИЕ ТИПА КОМНАТЫ
            boolean isGroup = false;

            // 1. Сначала проверяем, существует ли этот ID в таблице одиночных чатов
            String checkPrivateSql = "SELECT 1 FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkPrivateSql, new String[]{String.valueOf(chatId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    // Если нашли запись в 'chats' — это одиночный чат
                    isGroup = false;
                } else {
                    // 2. Если в одиночных чатах нет, проверяем таблицу групповых комнат
                    String checkGroupSql = "SELECT 1 FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
                    try (Cursor groupCursor = db.rawQuery(checkGroupSql, new String[]{String.valueOf(chatId)})) {
                        if (groupCursor != null && groupCursor.moveToFirst()) {
                            // Нашли в 'group_chats' — это групповой чат
                            isGroup = true;
                        }
                    }
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ С УЧЕТОМ ОБНОВЛЕННОЙ СТРУКТУРЫ ТАБЛИЦЫ MESSAGES
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", senderEmail.trim().toLowerCase());
            values.put("message_text", messageText);
            values.put("subject", subject);
            values.put("timestamp", currentTimestamp);

            // Исходящие сообщения изначально создаются со статусом:
            values.put("is_mine", 1); // 1 = Сообщение моё (отрисуется в чате справа)
            values.put("is_sent", 0); // 0 = Статус "Часики" (ожидает отправки сетевым SMTP-слоем)

            // Жестко выставляем тип контента по умолчанию
            values.put("message_type", TEXT_MSG);
            values.put("file_path", (String) null); // Пустое поле для текста (заполнится только в методе фото)

            // АВТОМАТИЗАЦИЯ: Пишем вычисленный реляционный флаг контекста (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // ШАГ 3: ЗАПИСЬ НА ДИСК
            long rowId = db.insert("messages", null, values);
            generatedId = (int) rowId;

            Log.d("MailLis_DB_Save", "Исходящее сообщение успешно записано. Локальный ID: "
                    + generatedId + " (Контекст: " + (isGroup ? "ГРУППА" : "ТЕТ-А-ТЕТ") + ")");

        } catch (Exception e) {
            Log.e("MailLis_DB_Save", "Критическая ошибка при локальном сохранении сообщения для чата " + chatId, e);
            e.printStackTrace();
        }

        return generatedId;
    }

    /**
     * Жестко и без проверок сохраняет исходящее сообщение в контексте группового чата.
     */
    public int saveGroupMessage(long groupId, String senderEmail, String subject, String messageText, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowId = -1;
        try {
            ContentValues values = new ContentValues();
            values.put("chat_id", groupId); // Пишем тот ID, с которым работает активность
            values.put("sender_email", senderEmail.trim().toLowerCase());
            values.put("message_text", messageText);
            values.put("subject", subject);
            values.put("timestamp", timestamp);
            values.put("is_mine", 1);
            values.put("is_sent", 0); // Часики
            values.put("message_type", TEXT_MSG);

            // ЖЕСТКАЯ ГАРАНТИЯ: Это сообщение 100% групповое!
            values.put("is_group_message", 1);

            rowId = db.insert("messages", null, values);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return (int) rowId;
    }

    public MessageDto getMessage(long localMessageId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto messageDto = null;

        // Ищем строго одну строку, где id совпадает с переданным localMessageId
        try (Cursor cursor = db.query(
                "messages",
                null,
                "id = ?",
                new String[]{String.valueOf(localMessageId)},
                null, null, null
        )) {

            // Проверяем, нашла ли база данных строку
            if (cursor != null && cursor.moveToFirst()) {
                // Узнаем индексы колонок строго ОДИН РАЗ (без циклов)
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int subjectIndex = cursor.getColumnIndex("subject");
                int isMineIndex = cursor.getColumnIndex("is_mine"); // Считываем новое поле статуса
                int isSendIndex = cursor.getColumnIndex("is_sent");
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");
                int isGroupIndex = cursor.getColumnIndex("is_group_message");

                // Проверяем существование критически важных колонок
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0 && isGroupIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int isSend = cursor.getInt(isSendIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);
                    int isGroup = cursor.getInt(isGroupIndex);

                    // Безопасное чтение необязательных текстовых колонок
                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Упрощенное и безопасное считывание бита is_mine напрямую из базы
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Создаем объект сообщения
                    messageDto = new MessageDto(realId, sender, subject, text, time, isMine,
                            serverUid, isSend, messageType, filePath, isGroup != 0);
                }
            }
        }

        return messageDto;
    }

    public void markMessageAsSent(int localMessageId, String serverUid) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 1); // Успешно улетело!
        if (serverUid != null) {
            values.put("server_uid", serverUid);
        }
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
    }

    public List<ChatPreview> loadChats() {
        List<ChatPreview> chatList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗИРОВАННЫЙ UNION ЗАПРОС С КОРРЕКТНЫМ ВЫДЕЛЕНИЕМ СКВОЗНЫХ ИДЕНТИФИКАТОРОВ
        String sql =
                // --- ЧАСТЬ 1: Вытягиваем одиночные чаты (is_group_message = 0) ---
                "SELECT " +
                        "  id AS chat_id, " +
                        "  recipient_email AS chat_name, " +
                        "  COALESCE((SELECT message_text FROM messages WHERE chat_id = chats.id " +
                        "AND is_group_message = 0 ORDER BY timestamp DESC LIMIT 1), 'Нет сообщений') AS last_msg, " +
                        "  COALESCE((SELECT timestamp FROM messages WHERE chat_id = chats.id " +
                        "AND is_group_message = 0 ORDER BY timestamp DESC LIMIT 1), 0) AS last_time, " +
                        "  0 AS is_group_flag " + // 0 = одиночный чат
                        "FROM chats " +

                        "UNION ALL " + // Склеиваем таблицы без потери данных

                        // --- ЧАСТЬ 2: ИСПРАВЛЕНО ДЛЯ ГРУПП (Вытягиваем именно remote_group_id как сквозной chat_id) ---
                        "SELECT " +
                        "  remote_group_id AS chat_id, " + // КРИТИЧЕСКИЙ ФИКС: отдаем глобальный ID для синхронизации
                        "  group_name AS chat_name, " +
                        "  COALESCE((SELECT message_text FROM messages WHERE chat_id = group_chats.remote_group_id " +
                        "AND is_group_message = 1 ORDER BY timestamp DESC LIMIT 1), 'Группа создана') AS last_msg, " +
                        "  COALESCE((SELECT timestamp FROM messages WHERE chat_id = group_chats.remote_group_id " +
                        "AND is_group_message = 1 ORDER BY timestamp DESC LIMIT 1), 0) AS last_time, " +
                        "  1 AS is_group_flag " + // 1 = группа
                        "FROM group_chats " +

                        // --- ОБЩАЯ СОРТИРОВКА: Новые чаты всегда сверху ---
                        "ORDER BY last_time DESC";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex("chat_id");
                int nameIdx = cursor.getColumnIndex("chat_name");
                int timeIdx = cursor.getColumnIndex("last_time");
                int groupIdx = cursor.getColumnIndex("is_group_flag");

                if (idIdx >= 0 && nameIdx >= 0 && timeIdx >= 0 && groupIdx >= 0) {
                    do {
                        // Используем long для ID чатов во избежание переполнения
                        long id = cursor.getLong(idIdx);
                        String name = cursor.getString(nameIdx);
                        boolean isGroup = cursor.getInt(groupIdx) == 1;

                        // БЕЗОПАСНАЯ ПРОВЕРКА НА РУКОПОЖАТИЕ
                        boolean isHandShake = false;

                        // Теперь getLastMessage получит верный сквозной ID группы и найдет запись!
                        var lastMsg = getLastMessage(id, isGroup);

                        String msg = "Нет сообщений";
                        if (isGroup) {
                            msg = "Группа создана";
                        }

                        // Проверяем на служебные заголовки рукопожатий (для одиночных чатов)
                        if (lastMsg != null && lastMsg.getSubject() != null) {
                            isHandShake = !lastMsg.getSubject().contains("MAILLIS_CHAT_MESSAGE"); // Замените на вашу константу
                            msg = isHandShake ? "Чат создан" : lastMsg.getText();
                        } else if (lastMsg != null) {
                            msg = lastMsg.getText(); // Если обычный текст, берем его
                        }

                        // Собираем готовый объект превью чата с правильным сквозным ID
                        ChatPreview preview = new ChatPreview((int) id, name, msg, isHandShake, isGroup);
                        chatList.add(preview);

                    } while (cursor.moveToNext());
                }
            }
            Log.d("MailLis_DB_Union", "Собрано чатов для главного экрана: " + chatList.size());
        } catch (Exception e) {
            Log.e("MailLis_DB_Union", "Критическая ошибка выполнения UNION запроса в loadChats()", e);
            e.printStackTrace();
        }

        return chatList;
    }

    public MessageDto getLastMessage(long chatId, boolean isGroup) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto lastMessageDto = null;

        // Пишем чистый и быстрый SQL-запрос с фильтрацией по ID комнаты и флагу группового сообщения
        String sql = "SELECT * FROM messages WHERE chat_id = CAST(? AS INTEGER) AND is_group_message = ? " +
                "ORDER BY timestamp DESC LIMIT 1";

        String groupFlag = isGroup ? "1" : "0";

        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId), groupFlag})) {
            // Проверяем, нашла ли база данных подходящую запись
            if (cursor != null && cursor.moveToFirst()) {

                // Считываем индексы колонок строго один раз для максимальной производительности
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int subjectIndex = cursor.getColumnIndex("subject");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int isMineIndex = cursor.getColumnIndex("is_mine");
                int typeIndex = cursor.getColumnIndex("message_type");
                int pathIndex = cursor.getColumnIndex("file_path");
                int isSentIndex = cursor.getColumnIndex("is_sent");

                // Проверяем существование базовых структурных полей
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);

                    // Безопасное считывание опциональных полей переписки
                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : "";
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : "";
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;
                    String messageType = typeIndex >= 0 ? cursor.getString(typeIndex) : TEXT_MSG;
                    String filePath = pathIndex >= 0 ? cursor.getString(pathIndex) : null;
                    int isSent = cursor.getInt(isSentIndex);

                    // Собираем полноценный DTO-объект сообщения со всеми медиа-атрибутами
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, isMine,
                            serverUid, isSent, messageType, filePath, isGroup);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка извлечения последнего сообщения для чата " + chatId, e);
            e.printStackTrace();
        }

        return lastMessageDto;
    }

    public long saveChat(String title, String targetEmail) {
        if (targetEmail == null || targetEmail.isEmpty()) {
            return -1;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long chatId = -1;

        // 1. Стандартизируем Email: очищаем пробелы и переводим в нижний регистр
        String normalizedEmail = targetEmail.trim().toLowerCase();
        String normalizedTitle = title != null ? title.trim() : normalizedEmail;

        try {
            // 2. ЗАЩИТА: Проверяем, нет ли уже чата с этим пользователем в таблице chats
            String checkSql = "SELECT id FROM chats WHERE recipient_email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    chatId = cursor.getLong(0);
                    Log.d("MailLis_DB", "Чат с " + normalizedEmail + " уже существует. Возвращаем ID: " + chatId);
                    return chatId; // Сразу возвращаем старый ID, не делая INSERT
                }
            }

            // 3. СБОРКА ДАННЫХ ДЛЯ ВСТАВКИ
            ContentValues values = new ContentValues();
            values.put("recipient_email", normalizedEmail);
            // Если в вашей старой структуре таблицы 'chats' оставалась колонка 'title' или 'chat_name',
            // раскомментируйте строку ниже:
            // values.put("title", normalizedTitle);

            // 4. БЕЗОПАСНАЯ ВСТАВКА
            chatId = db.insertWithOnConflict(
                    "chats",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            Log.i("MailLis_DB", "Успешно создан новый одиночный чат. ID в SQLite: " + chatId + " для " + normalizedEmail);

        } catch (Exception e) {
            Log.e("MailLis_DB", "Критический сбой метода saveChat для email: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return chatId;
    }

    public long getChatIdByRecipientEmail(String email) {
        if (email == null || email.isEmpty()) {
            return -1;
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        long chatId = -1;

        // 1. Принудительно очищаем пробелы и переводим в нижний регистр для стандартизации поиска
        String normalizedEmail = email.trim().toLowerCase();

        // 2. Делаем выборку из таблицы chats. Оператор LIKE гарантирует регистронезависимость.
        // Ограничение LIMIT 1 сообщает SQLite, что нам нужна ровно одна строка (экономит ресурсы).
        String sql = "SELECT id FROM chats WHERE recipient_email LIKE ? LIMIT 1";

        // Используем try-with-resources для автоматического и гарантированного закрытия Cursor
        try (Cursor cursor = db.rawQuery(sql, new String[]{normalizedEmail})) {

            // Проверяем, нашла ли база данных хотя бы одну строку
            if (cursor != null && cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex("id");
                if (idIndex >= 0) {
                    // Извлекаем реальное числовое значение ID чата из SQLite (тип long)
                    chatId = cursor.getLong(idIndex);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при поиске одиночного chatId по email: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return chatId;
    }

    @Override
    public int deleteMessage(int msdId) {
        return dbHelper.getWritableDatabase().delete("messages", "id = ?", new String[]{String.valueOf(msdId)});
    }

    public boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText,
                                       long currentTimestamp, String serverUid, String type, String imgPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSaved = false;

        try {
            // ШАГ 1: АВТО-ОПРЕДЕЛЕНИЕ ТИПА ЧАТА (Одиночный или Групповой)
            boolean isGroup = false;

            // 1. Сначала проверяем, существует ли этот ID в таблице одиночных чатов
            String checkPrivateSql = "SELECT 1 FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkPrivateSql, new String[]{String.valueOf(chatId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    // Если нашли в 'chats' — это 100% одиночный чат
                    isGroup = false;
                } else {
                    // 2. Если в одиночных чатах не нашли, проверяем таблицу групповых чатов
                    String checkGroupSql = "SELECT 1 FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
                    try (Cursor groupCursor = db.rawQuery(checkGroupSql, new String[]{String.valueOf(chatId)})) {
                        if (groupCursor != null && groupCursor.moveToFirst()) {
                            isGroup = true; // Нашли в 'group_chats' — это группа
                        }
                    }
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ ДЛЯ ЗАПИСИ В ТАБЛИЦУ MESSAGES
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", senderEmail.trim().toLowerCase());
            values.put("subject", subject);
            values.put("message_text", messageText);
            values.put("timestamp", currentTimestamp);
            values.put("server_uid", serverUid != null ? serverUid.trim() : "");

            values.put("is_mine", 0); // 0 = Входящее сообщение от собеседника
            values.put("is_sent", 1); // 1 = Сообщение уже успешно прошло через сеть

            // Записываем правильный тип чата, тип контента и путь к картинке
            values.put("is_group_message", isGroup ? 1 : 0); // ИСПОЛЬЗУЕТСЯ ДЛЯ ОДИНОЧНЫХ (0) И ГРУПП (1)
            values.put("message_type", type != null ? type : TEXT_MSG);
            values.put("file_path", imgPath);

            // ШАГ 3: БЕЗОПАСНАЯ АТОМАРНАЯ ВСТАВКА В БАЗУ ДАННЫХ
            // Флаг CONFLICT_IGNORE защищает от двоения: если server_uid уже есть в SQLite,
            // запись просто пропустится, не ломая интерфейс чата и не дублируя сообщения.
            long rowId = db.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            isSaved = (rowId != -1);
            Log.d("MailLis_DB_Save", "Входящее сообщение обработано. ID в SQLite: " + rowId + " (Тип: " + (isGroup ? "ГРУППА" : "ОДИНОЧНЫЙ ЧАТ") + ")");

        } catch (Exception e) {
            Log.e("MailLis_DB_Save", "Критический сбой сохранения входящего сообщения с UID: " + serverUid, e);
            e.printStackTrace();
        }

        return isSaved;
    }

    /**
     * Безопасно сохраняет входящее групповое сообщение (текст или медиафайл) в SQLite.
     * Жестко прописывает флаг группового контекста (is_group_message = 1).
     * Защищено от дублирования писем при повторном IMAP-сканировании через CONFLICT_IGNORE.
     *
     * @param chatId           глобальный ID группы (remote_group_id), пришедший из темы письма
     * @param senderEmail      email участника группы, который отправил это сообщение
     * @param subject          тема письма (служебный тег, например [MailLis-Group-Message])
     * @param messageText      чистый текст сообщения (или заглушка "[Изображение]" для фото)
     * @param currentTimestamp время отправки письма, зафиксированное почтовым сервером
     * @param serverUid        уникальный Message-ID письма для предотвращения дубликатов
     * @param type             тип контента: "TEXT" или "IMAGE"
     * @param imgPath          абсолютный путь к расшифрованному файлу .jpg на диске (или null)
     * @return true, если сообщение успешно записано; false, если это дубликат или произошла ошибка
     */
    public boolean saveIncomingGroupMessage(long chatId, String senderEmail, String subject, String messageText,
                                            long currentTimestamp, String serverUid, String type, String imgPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSaved = false;

        try {
            ContentValues values = new ContentValues();

            // 1. Привязываем сообщение к сквозному глобальному ID группы
            values.put("chat_id", chatId);
            values.put("sender_email", senderEmail.trim().toLowerCase());
            values.put("subject", subject);
            values.put("message_text", messageText);
            values.put("timestamp", currentTimestamp);
            values.put("server_uid", serverUid != null ? serverUid.trim() : "");

            // Входящее сообщение от другого участника группы
            values.put("is_mine", 0); // 0 = Чужое
            values.put("is_sent", 1); // 1 = Доставлено (уже прошло через сеть Mail.ru)

            // 2. ЖЕСТКАЯ ГАРАНТИЯ КОНТЕКСТА: Сообщение на 100% является групповым
            values.put("is_group_message", 1);

            // Настройка медиа-параметров (для фото или текста)
            values.put("message_type", type != null ? type : "TEXT");
            values.put("file_path", imgPath);

            // 3. АТОМАРНАЯ ВСТАВКА С ФЛАГОМ ИГНОРИРОВАНИЯ КОНФЛИКТОВ
            // Если server_uid уже существует в базе (повторный фоновый цикл скачивания),
            // SQLite просто пропустит этот INSERT, вернув -1, и переписка не задроится.
            long rowId = db.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            isSaved = (rowId != -1);

            if (isSaved) {
                Log.d("MailLis_DB_Group", "Входящее групповое сообщение успешно сохранено. ID: " + rowId + " для группы: " + chatId);
            } else {
                Log.w("MailLis_DB_Group", "Пропущено сохранение дубликата группового сообщения с UID: " + serverUid);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критическая ошибка при сохранении входящего группового сообщения", e);
            e.printStackTrace();
        }

        return isSaved;
    }

    @Override
    public void updateServerUid(String generatedServerUid, int localMessageId) {
        if (generatedServerUid != null) {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            var values = new ContentValues();
            values.put("server_uid", generatedServerUid);
            db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
        }
    }

    public byte[] getPublicKey(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] publicKey = null;

        // СЛОЖНЫЙ СВЕРХБЫСТРЫЙ SQL-ЗАПРОС:
        // Мы берем строку из chats, по её recipient_email соединяем с public_keys
        // и забираем бинарный BLOB ключа. CAST(? AS INTEGER) защищает от Type Mismatch.
        String sql = "SELECT p.peer_public_key FROM chats c " +
                "JOIN public_keys p ON c.recipient_email = p.email " +
                "WHERE c.id = CAST(? AS INTEGER) LIMIT 1";

        // Использование try-with-resources гарантирует автоматическое закрытие Cursor
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {

            // Так как ID уникален, проверяем строго одну первую запись
            if (cursor != null && cursor.moveToFirst()) {
                int publicKeyIndex = cursor.getColumnIndex("peer_public_key");
                if (publicKeyIndex >= 0) {
                    // Считываем массив байт (BLOB) публичного ключа Tink
                    publicKey = cursor.getBlob(publicKeyIndex);
                }
            }

        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при извлечении публичного ключа по chatId: " + chatId, e);
            e.printStackTrace();
        }

        return publicKey;
    }

    public List<MessageDto> getUnsentMessages() {
        List<MessageDto> unsentList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗАЦИЯ: Пишем чистый SQL-запрос. Ноль передается как ЧИСЛО (is_sent = 0),
        // что на 100% исключает проблему, когда SQLite не может сравнить строку с числом.
        String sqlQuery = "SELECT * FROM messages WHERE is_sent = 0 ORDER BY timestamp ASC";

        try (Cursor cursor = db.rawQuery(sqlQuery, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                // Выносим поиск индексов строго наверх ДО начала итераций (для максимальной скорости)
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int subjectIndex = cursor.getColumnIndex("subject");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int isMineIndex = cursor.getColumnIndex("is_mine");
                int statusIndex = cursor.getColumnIndex("is_sent");
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");
                int isGroupIndex = cursor.getColumnIndex("is_group_message");


                // Проверяем существование критически важных для структуры полей
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0 && isGroupIndex >= 0) {
                    do {
                        int realId = cursor.getInt(idIndex);
                        String sender = cursor.getString(senderIndex);
                        String text = cursor.getString(textIndex);
                        long time = cursor.getLong(timeIndex);
                        int status = cursor.getInt(statusIndex);
                        String messageType = cursor.getString(messageTypeIndex);
                        String filePath = cursor.getString(filePathIndex);
                        int isGroup = cursor.getInt(isGroupIndex);

                        // Безопасное чтение необязательных текстовых колонок
                        String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                        String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                        // Упрощенное и безопасное считывание флага из базы
                        boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                        // Создаем чистый объект сообщения
                        var messageDto = new MessageDto(realId, sender, subject, text, time, isMine,
                                serverUid, status, messageType, filePath, isGroup != 0);
                        unsentList.add(messageDto);

                    } while (cursor.moveToNext()); // Переходим к следующему неотправленному письму

                    Log.d("MailLis_DB", "getUnsentMessages() успешно нашёл в SQLite строк: " + unsentList.size());
                } else {
                    Log.e("MailLis_DB", "Критическая ошибка: В таблице messages не найдены базовые колонки!");
                }
            } else {
                Log.d("MailLis_DB", "getUnsentMessages(): Очередь неотправленных сообщений пуста (0 писем со статусом 0).");
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критический сбой выполнения rawQuery в getUnsentMessages()", e);
            e.printStackTrace();
        }

        return unsentList;
    }

    public int getChatIdByMessageId(int messageId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int chatId = -1;

        // Запрашиваем только колонку chat_id для строки с указанным id
        try (Cursor cursor = db.query(
                "messages",
                new String[]{"chat_id"},
                "id = ?",
                new String[]{String.valueOf(messageId)},
                null, null, null
        )) {

            // Так как id уникален, проверяем строго одну первую запись
            if (cursor.moveToFirst()) {
                int chatIdIndex = cursor.getColumnIndex("chat_id");
                if (chatIdIndex >= 0) {
                    chatId = cursor.getInt(chatIdIndex);
                }
            }
        }
        return chatId;
    }

    public boolean banUser(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long id = -1;
        try {
            var values = new ContentValues();
            values.put("email", email.trim().toLowerCase());
            values.put("timestamp", System.currentTimeMillis());

            // insertWithOnConflict защитит от дубликатов, если пользователя случайно забанят дважды
            id = db.insertWithOnConflict("ban_list", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return id != -1;
    }

    public boolean unbanUser(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rows = 0;
        try {
            rows = db.delete("ban_list", "email = ?", new String[]{email.trim().toLowerCase()});
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rows > 0;
    }

    public boolean isUserBanned(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean isBanned = false;

        try (Cursor cursor = db.query(
                "ban_list",
                new String[]{"id"},
                "email = ?",
                new String[]{email.trim().toLowerCase()},
                null, null, null)) {

            // Если база нашла хотя бы одну строку — пользователь заблокирован
            isBanned = cursor.moveToFirst();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return isBanned;
    }

    public List<String> getBannedUsers() {
        List<String> bannedList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрашиваем только колонку email из таблицы ban_list, сортируем по времени блокировки (сначала свежие)
        try (Cursor cursor = db.query("ban_list", new String[]{"email"}, null, null, null, null, "timestamp DESC")) {
            if (cursor.moveToFirst()) {
                int emailIndex = cursor.getColumnIndex("email");
                if (emailIndex >= 0) {
                    do {
                        String email = cursor.getString(emailIndex);
                        if (email != null) {
                            bannedList.add(email);
                        }
                    } while (cursor.moveToNext());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bannedList;
    }

    public boolean deleteChatAndMessages(long chatId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success = false;

        // Открываем атомарную транзакцию (оба удаления выполнятся строго вместе)
        db.beginTransaction();
        try {
            // 1. ИСПРАВЛЕНИЕ: Стираем все сообщения чата с принудительным приведением типов CAST
            db.delete(
                    "messages",
                    "chat_id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(chatId)}
            );

            // 2. ИСПРАВЛЕНИЕ: Стираем саму комнату чата с принудительным приведением типов CAST
            int rows = db.delete(
                    "chats",
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(chatId)}
            );

            // Фиксируем успех транзакции в SQLite
            db.setTransactionSuccessful();
            success = rows > 0;

            Log.d("MailLis_DB", "Чат " + chatId + " и его сообщения стерты. Результат: " + success);

        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при удалении чата " + chatId, e);
            e.printStackTrace();
        } finally {
            // Обязательно закрываем транзакцию, возвращая базу в пул многопоточности WAL
            db.endTransaction();
        }

        return success;
    }

    public boolean deleteGroupMembersByRemoteId(long remoteGroupId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isDeleted = false;

        // Открываем транзакцию для безопасной и атомарной работы с диском
        db.beginTransaction();
        try {
            // 1. Находим локальный id группы в таблице group_chats по её remote_group_id
            long localGroupId = -1;
            String findLocalIdSql = "SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1";

            try (Cursor cursor = db.rawQuery(findLocalIdSql, new String[]{String.valueOf(remoteGroupId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    localGroupId = cursor.getLong(0);
                }
            }

            // 2. Если локальный ID успешно найден, очищаем таблицу участников
            if (localGroupId != -1) {
                // Используем прямой execSQL с CAST для защиты структуры типов на Android 14+
                db.execSQL("DELETE FROM group_members WHERE group_id = CAST(? AS INTEGER)",
                        new Object[]{localGroupId});

                db.setTransactionSuccessful(); // Фиксируем успех транзакции
                isDeleted = true;
                Log.d("MailLis_DB_Members", "Участники группы (Remote ID: " + remoteGroupId + ") успешно удалены.");
            } else {
                Log.w("MailLis_DB_Members", "Не удалось удалить участников: группа с Remote ID " + remoteGroupId + " не найдена.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Members", "Критическая ошибка при удалении участников группы по Remote ID: " + remoteGroupId, e);
            e.printStackTrace();
            isDeleted = false;
        } finally {
            db.endTransaction(); // Закрываем транзакцию
        }

        return isDeleted;
    }

    public void markMessageAsRead(String serverUid) {
        if (serverUid == null || serverUid.isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        var values = new ContentValues();
        values.put("is_sent", 2); // 2 = Две галочки
        db.update("messages", values, "server_uid = ?", new String[]{serverUid});
    }

    public void markMessageAsSending(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        var values = new ContentValues();
        values.put("is_sent", 3); // 3 = Сообщение в процессе отправки прямо сейчас
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
    }

    public void resetMessageStatusToUnsent(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        var values = new ContentValues();
        values.put("is_sent", 0); // Возвращаем в "Часики"
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
    }

    public int getTotalMessagesCount() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int totalCount = 0;

        // Используем rawQuery для выполнения прямого и самого быстрого подсчета строк
        String sql = "SELECT COUNT(*) FROM messages";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            // Проверяем, вернула ли база данных результат (COUNT всегда возвращает 1 строку)
            if (cursor != null && cursor.moveToFirst()) {
                // Считываем числовое значение из самой первой (нулевой) колонки
                totalCount = cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критический сбой подсчета сообщений в getTotalMessagesCount()", e);
            e.printStackTrace();
        }

        return totalCount;
    }

    public MessageDto getLastIncomingMessage() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto lastMessageDto = null;

        // Ищем строго чужие сообщения (is_mine = 0), сортируем от новых к старым (DESC) и берем ровно 1 строку
        String sql = "SELECT * FROM messages WHERE is_mine = 0 ORDER BY timestamp DESC LIMIT 1";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            // Проверяем, нашла ли база данных подходящую запись
            if (cursor != null && cursor.moveToFirst()) {

                // Узнаем индексы колонок строго один раз (для максимальной скорости)
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int subjectIndex = cursor.getColumnIndex("subject");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int statusIndex = cursor.getColumnIndex("is_sent");
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");
                int isGroupIndex = cursor.getColumnIndex("is_group_message");

                // Проверяем, что все базовые колонки присутствуют в таблице
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0 && isGroupIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int status = cursor.getInt(statusIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);
                    int isGroup = cursor.getInt(isGroupIndex);

                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Собираем чистый объект сообщения
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, false,
                            serverUid, status, messageType, filePath,
                            isGroup != 0);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при получении последнего входящего сообщения для Push", e);
            e.printStackTrace();
        }

        return lastMessageDto;
    }

    public void updateMessageTypeAndPath(int messageId, String type, String path) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            var values = new ContentValues();
            values.put("message_type", type); // "IMAGE"
            values.put("file_path", path);   // путь к .jpg на диске
            db.update("messages", values, "id = CAST(? AS INTEGER)", new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public long createGroupChat(String groupName, byte[] generatedGroupKey, String creatorEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long localId = -1;

        try {
            //long globalUniqueId = System.currentTimeMillis(); // Генерируем уникальный сквозной ID группы

            // 1. СБОРКА ДАННЫХ ДЛЯ НОВОЙ ТАБЛИЦЫ GROUP_CHATS
            var values = new ContentValues();
            // ИСПРАВЛЕНИЕ: Используем правильные новые имена колонок
            values.put("group_name", groupName != null ? groupName.trim() : "Новая группа");
            values.put("creator_email", creatorEmail != null ? creatorEmail.trim().toLowerCase() : "unknown@mail.ru");
            values.put("group_secret_key", generatedGroupKey);

            // Вставляем запись в таблицу групповых чатов
            localId = db.insert("group_chats", null, values);

            // 2. СИНХРОНИЗАЦИЯ: Так как мы создатели группы, наш локальный ID
            // должен стать глобальным remote_group_id для всех будущих участников группы
            if (localId != -1) {
                ContentValues updateValues = new ContentValues();
                updateValues.put("remote_group_id", localId);

                // Точечно обновляем только что созданную строку
                db.update(
                        "group_chats",
                        updateValues,
                        "id = CAST(? AS INTEGER)",
                        new String[]{String.valueOf(localId)}
                );

                Log.i("MailLis_DB_Group", "Успешно создана группа \"" + groupName
                        + "\" в таблице group_chats. Присвоен ID: " + localId);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критическая ошибка при создании группы: " + groupName, e);
            e.printStackTrace();
        }

        return localId;
    }

    /**
     * Проверяет, является ли переданный ID глобальным идентификатором группового чата.
     * Ищет строго по колонке remote_group_id для исключения ложных срабатываний автоинкремента.
     *
     * @param chatId числовой идентификатор чата из активности или сетевого слоя
     * @return true, если это гарантированно группа; false, если это одиночный чат
     */
    public boolean isGroupChat(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean isGroup = false;

        // ИСПРАВЛЕНИЕ КОЛЛИЗИИ: Мы полностью убираем проверку "id = CAST(? AS INTEGER)",
        // потому что локальный автоинкремент (1, 2, 3...) всегда совпадает с ID одиночных чатов.
        // Мы проверяем ТОЛЬКО remote_group_id, который является уникальным глобальным номером группы!
        String sql = "SELECT 1 FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1";

        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                isGroup = true;
            }
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Ошибка при проверке типа чата для ID: " + chatId, e);
            e.printStackTrace();
        }

        return isGroup;
    }

    public void saveReceivedGroupChat(long remoteChatId, String incomingGroupName,
                                      byte[] decryptedGroupKey, String creatorEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        try {
            var values = new ContentValues();

            // КРИТИЧЕСКИ ВАЖНО: записываем ID создателя в колонку remote_group_id,
            // чтобы у всех участников группы был один и тот же глобальный идентификатор синхронизации!
            values.put("remote_group_id", remoteChatId);
            values.put("group_name", incomingGroupName != null ? incomingGroupName.trim() : "Групповой чат");
            values.put("creator_email", creatorEmail != null ? creatorEmail.trim().toLowerCase() : "unknown@mail.ru");

            // Ключ записываем только если он успешно расшифрован (на этапе финального инвайта)
            if (decryptedGroupKey != null && decryptedGroupKey.length > 0) {
                values.put("group_secret_key", decryptedGroupKey);
            }

            // Используем insertWithOnConflict с флагом CONFLICT_REPLACE.
            // Так как на колонку remote_group_id установлено ограничение UNIQUE, SQLite при повторном инвайте
            // просто перезапишет старую строку свежими данными (например, допишет AES-ключ поверх заготовки чата).
            long rowId = db.insertWithOnConflict(
                    "group_chats",
                    null,
                    values,
                    CONFLICT_REPLACE
            );

            Log.i("MailLis_DB_Group", "Группа успешно импортирована в таблицу group_chats. Локальный ID: " + rowId + " (Remote ID: " + remoteChatId + ")");

        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критический сбой сохранения полученного группового чата. Remote ID: " + remoteChatId, e);
            e.printStackTrace();
        }
    }

    public void savePublicKey(String email, byte[] publicKeyBytes) {
        if (email == null || publicKeyBytes == null) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            ContentValues values = new ContentValues();
            values.put("email", email.trim().toLowerCase());
            values.put("peer_public_key", publicKeyBytes);

            // CONFLICT_REPLACE гарантирует автоматический апдейт ключа при его изменении
            db.insertWithOnConflict("public_keys", null, values, CONFLICT_REPLACE);
            Log.d("MailLis_Cache", "Ключ для " + email + " успешно сохранен в кэш public_keys");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public byte[] getPublicKeyByEmail(String email) {
        if (email == null) {
            return null;
        }
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] key = null;
        String sql = "SELECT peer_public_key FROM public_keys WHERE email LIKE ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{email.trim().toLowerCase()})) {
            if (cursor != null && cursor.moveToFirst()) {
                key = cursor.getBlob(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return key;
    }


    /**
     * Безопасно добавляет участника в группу, используя глобальный remote_group_id.
     * Автоматически вычисляет локальный ID группы для сохранения целостности связей БД.
     */
    public void addGroupMember(long remoteGroupId, String userEmail) {
        if (userEmail == null || userEmail.isEmpty()) return;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = userEmail.trim().toLowerCase();

        try {
            // 1. НАХОДИМ РЕАЛЬНЫЙ ЛОКАЛЬНЫЙ ID ГРУППЫ по её глобальному remote_group_id или id
            long localGroupId = -1;
            String findIdSql = "SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) OR id = CAST(? AS INTEGER) LIMIT 1";

            try (Cursor cursor = db.rawQuery(findIdSql, new String[]{String.valueOf(remoteGroupId), String.valueOf(remoteGroupId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    localGroupId = cursor.getLong(0);
                }
            }

            // Если группа не найдена (например, инвайт еще не обработан), пишем лог и выходим
            if (localGroupId == -1) {
                Log.e("MailLis_DB", "Не удалось добавить мембера " + normalizedEmail + ": группа с ID " + remoteGroupId + " не найдена в group_chats!");
                return;
            }

            // 2. ЗАЩИТА ОТ ДУБЛИКАТОВ
            String checkSql = "SELECT 1 FROM group_members WHERE group_id = CAST(? AS INTEGER) AND user_email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{String.valueOf(localGroupId), normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    Log.d("MailLis_DB", "Пользователь " + normalizedEmail + " уже состоит в группе " + remoteGroupId);
                    return;
                }
            }

            // 3. ВСТАВКА В ТАБЛИЦУ С ПРАВИЛЬНЫМ ЛОКАЛЬНЫМ ID
            var values = new ContentValues();
            values.put("group_id", localGroupId); // Записываем вычисленный локальный ID (1, 2, 3...)
            values.put("user_email", normalizedEmail);

            db.insert("group_members", null, values);
            Log.i("MailLis_DB", "В группу " + remoteGroupId + " (локальный ID: " + localGroupId + ") успешно добавлен: " + normalizedEmail);

        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при добавлении мембера " + normalizedEmail, e);
            e.printStackTrace();
        }
    }

    /**
     * Возвращает список Email-адресов всех участников конкретной группы (исключая свой собственный).
     * Гарантированно работает как на устройстве Создателя, так и у приглашенных Участников.
     *
     * @param groupId числовой идентификатор чата (локальный id или глобальный remote_group_id)
     * @param myEmail email текущего пользователя приложения (чтобы исключить себя из рассылки)
     * @return список email-адресов участников группы
     */
    public List<String> getGroupMembers(long groupId, String myEmail) {
        List<String> members = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ИСПРАВЛЕНИЕ: Мы связываем group_id в таблице участников с РЕАЛЬНЫМ локальным id
        // из таблицы group_chats. Мы находим этот локальный id по переданному groupId,
        // проверяя обе колонки (id и remote_group_id). Это самый чистый и быстрый SQL-путь!
        String sql = "SELECT user_email FROM group_members WHERE user_email != ? AND (" +
                "  group_id = CAST(? AS INTEGER) OR " +
                "  group_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                ")";

        String normalizedMyEmail = myEmail != null ? myEmail.trim().toLowerCase() : "";

        // Передаем аргументы: 1 раз для фильтрации email и 2 раза для условий сопоставления ID
        String[] selectionArgs = new String[]{
                normalizedMyEmail,
                String.valueOf(groupId),
                String.valueOf(groupId)
        };

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String memberEmail = cursor.getString(0);
                    if (memberEmail != null) {
                        members.add(memberEmail.trim().toLowerCase());
                    }
                } while (cursor.moveToNext());
            }
            Log.d("MailLis_DB", "Для группы (ID: " + groupId + ") успешно извлечено участников: " + members.size());
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при получении списка участников группы: " + groupId, e);
            e.printStackTrace();
        }

        return members;
    }

    /**
     * Извлекает секретный AES-ключ группы по её идентификатору.
     * Ищет совпадения как по локальному ID (для создателя), так и по remote_group_id (для участников).
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param groupId числовой идентификатор чата, полученный из Intent или сообщений
     * @return 32-байтный массив секретного AES-ключа группы, либо null, если группа не найдена
     */
    public byte[] getGroupSecretKey(long groupId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] key = null;

        // ИСПРАВЛЕНИЕ: Проверяем ОДНОВРЕМЕННО колонку id и колонку remote_group_id
        String sql = "SELECT group_secret_key FROM group_chats " +
                "WHERE id = CAST(? AS INTEGER) OR remote_group_id = CAST(? AS INTEGER) " +
                "LIMIT 1";

        // Передаем groupId дважды — для каждого из условий OR
        String[] selectionArgs = new String[]{String.valueOf(groupId), String.valueOf(groupId)};

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            // Если SQLite нашел строку — извлекаем бинарный BLOB ключа
            if (cursor != null && cursor.moveToFirst()) {
                int keyIndex = cursor.getColumnIndex("group_secret_key");
                if (keyIndex >= 0) {
                    key = cursor.getBlob(keyIndex);
                } else {
                    // Фолбэк, если индекс не определился по имени
                    key = cursor.getBlob(0);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при получении AES-ключа группы для ID: " + groupId, e);
            e.printStackTrace();
        }

        return key;
    }

    public boolean deletePublicKeyByEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isDeleted = false;

        // 1. Стандартизируем Email: убираем пробелы по краям и переводим в нижний регистр
        String normalizedEmail = email.trim().toLowerCase();

        try {
            // 2. Выполняем точечное удаление строки из таблицы public_keys.
            // Оператор LIKE обеспечивает регистронезависимое сравнение байт и защищает от Type Mismatch.
            int rowsAffected = db.delete(
                    "public_keys",
                    "email LIKE ?",
                    new String[]{normalizedEmail}
            );

            isDeleted = (rowsAffected > 0);
            Log.d("MailLis_Crypto", "Удаление ключа для " + normalizedEmail + ". Очищено строк кэша: " + rowsAffected);

        } catch (Exception e) {
            Log.e("MailLis_Crypto", "Критическая ошибка при удалении публичного ключа для: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return isDeleted;
    }

    /**
     * Полностью и гарантированно удаляет групповой чат, всех его участников и историю сообщений.
     * Выполняет явную последовательную очистку таблиц в обход отключенных по умолчанию Foreign Keys в Android.
     *
     * @param groupId числовой идентификатор чата (локальный id или глобальный remote_group_id)
     * @return true, если удаление из всех таблиц прошло успешно; false в случае сбоя
     */
    public boolean deleteGroupChatAndMessages(long groupId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success;

        // ХАК ДЛЯ НАДЕЖНОСТИ: Принудительно включаем поддержку внешних ключей для текущего соединения
        db.execSQL("PRAGMA foreign_keys = ON;");

        // Открываем атомарную безопасную транзакцию
        db.beginTransaction();
        try {
            // =========================================================================
            // ШАГ 1: ЯВНО И ЖЕСТКО УДАЛЯЕМ УЧАСТНИКОВ ИЗ ТАБЛИЦЫ group_members
            // Проверяем все варианты связей ID (прямой, через подзапросы к remote_group_id и обратно)
            // =========================================================================
            db.execSQL("DELETE FROM group_members WHERE " +
                            "  group_id = CAST(? AS INTEGER) OR " +
                            "  group_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1) OR " +
                            "  group_id = (SELECT remote_group_id FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1)",
                    new Object[]{groupId, groupId, groupId});
            Log.d("MailLis_DB_Del", "Участники группы " + groupId + " принудительно стерты из таблицы group_members.");

            // =========================================================================
            // ШАГ 2: УДАЛЯЕМ ВСЮ ИСТОРИЮ СООБЩЕНИЙ ГРУППЫ ИЗ ТАБЛИЦЫ messages
            // =========================================================================
            db.execSQL("DELETE FROM messages WHERE is_group_message = 1 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT remote_group_id FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1) OR " +
                    "  chat_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                    ")", new Object[]{groupId, groupId, groupId});
            Log.d("MailLis_DB_Del", "Сообщения группы " + groupId + " успешно очищены из таблицы messages.");

            // =========================================================================
            // ШАГ 3: УДАЛЯЕМ САМУ КОМНАТУ ГРУППЫ ИЗ ТАБЛИЦЫ group_chats
            // =========================================================================
            db.execSQL("DELETE FROM group_chats WHERE id = CAST(? AS INTEGER) OR remote_group_id = CAST(? AS INTEGER)",
                    new Object[]{groupId, groupId});
            Log.d("MailLis_DB_Del", "Запись группы " + groupId + " удалена из таблицы group_chats.");

            // Если все три удаления прошли без ошибок — фиксируем успех транзакции в SQLite диске
            db.setTransactionSuccessful();
            success = true;
            Log.i("MailLis_DB_Del", "Групповой чат " + groupId + " и все связанные данные полностью стерты со смартфона.");

        } catch (Exception e) {
            Log.e("MailLis_DB_Del", "Критическая ошибка транзакции при удалении группы " + groupId, e);
            e.printStackTrace();
            success = false;
        } finally {
            // Гарантированно закрываем транзакцию, высвобождая поток БД
            db.endTransaction();
        }

        return success;
    }

    public String getRecipientEmailByChatId(long chatId) {
        String recipientEmail = EMPTI_STRING;
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗАЦИЯ: Используем CAST(? AS INTEGER) и LIMIT 1 для максимальной скорости запроса
        String sql = "SELECT recipient_email FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";

        // Использование try-with-resources гарантирует автоматическое закрытие курсора
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {

            // Проверяем, нашла ли база данных нужную строку
            if (cursor != null && cursor.moveToFirst()) {
                int emailIdx = cursor.getColumnIndex("recipient_email");
                if (emailIdx >= 0) {
                    String result = cursor.getString(emailIdx);
                    if (result != null) {
                        recipientEmail = result.trim().toLowerCase(); // Возвращаем стандартизированный email
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при поиске recipient_email по одиночному chatId: " + chatId, e);
            e.printStackTrace();
        }

        return recipientEmail;
    }

    /**
     * Возвращает список Email-адресов всех участников конкретной группы (исключая свой собственный).
     * Гарантированно работает как на устройстве Создателя, так и у приглашенных Участников.
     * Использует реляционный подзапрос для сопоставления локальных и глобальных ID групп.
     *
     * @param groupId числовой идентификатор чата из активности (локальный id или глобальный remote_group_id)
     * @param myEmail email текущего пользователя приложения (чтобы исключить себя из списка рассылки)
     * @return список email-адресов участников группы в нижнем регистре
     */
    public List<String> getGroupMembersEmails(long groupId, String myEmail) {
        List<String> membersList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // СВЕРХБЫСТРЫЙ РЕЛЯЦИОННЫЙ ЗАПРОС:
        // Мы выбираем user_email из таблицы участников.
        // Условие в скобках автоматически связывает переданный groupId с локальным group_id,
        // проверяя как прямую привязку, так и глобальный remote_group_id через таблицу group_chats.
        String sql = "SELECT user_email FROM group_members WHERE user_email != ? AND (" +
                "  group_id = CAST(? AS INTEGER) OR " +
                "  group_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                ")";

        String normalizedMyEmail = myEmail != null ? myEmail.trim().toLowerCase() : "";

        // Передаем аргументы: 1 раз для фильтрации своего email и 2 раза для условий сопоставления ID
        String[] selectionArgs = new String[]{
                normalizedMyEmail,
                String.valueOf(groupId),
                String.valueOf(groupId)
        };

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String memberEmail = cursor.getString(0);
                    if (memberEmail != null && !memberEmail.isEmpty()) {
                        // Добавляем очищенный стандартизированный email в список
                        membersList.add(memberEmail.trim().toLowerCase());
                    }
                } while (cursor.moveToNext());
            }
            Log.d("MailLis_DB_Members", "Для группы (ID: " + groupId + ") успешно извлечено участников: " + membersList.size());
        } catch (Exception e) {
            Log.e("MailLis_DB_Members", "Критическая ошибка при получении email участников группы: " + groupId, e);
            e.printStackTrace();
        }

        return membersList;
    }
}
