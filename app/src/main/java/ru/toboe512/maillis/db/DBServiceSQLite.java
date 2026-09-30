package ru.toboe512.maillis.db;

import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

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


    /**
     * Извлекает полную историю переписки для конкретного экрана ChatActivity.
     * Полностью адаптирован под раздельную структуру таблиц одиночных чатов и групп.
     * Сортирует сообщения от старых к новым для правильного отображения в RecyclerView.
     *
     * @param chatId  локальный идентификатор комнаты (из таблицы chats или group_chats)
     * @param isGroup флаг типа чата: true - если это группа, false - если одиночный Тет-а-Тет
     * @return список объектов Message, готовый для передачи в DiffUtil и адаптер
     */
    public List<MessageDto> getAllMessages(long chatId, boolean isGroup) {
        List<MessageDto> messageDtoList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Выбираем сообщения строго по ID чата И по флагу группового сообщения (0 или 2)
        String sql = "SELECT * FROM messages WHERE chat_id = CAST(? AS INTEGER) AND is_group_message = ? ORDER BY timestamp ASC";

        // Переводим логический флаг в числовой вид для SQLite ("1" или "0")
        String groupFlag = isGroup ? "1" : "0";

        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId), groupFlag})) {
            // Проверяем, вернула ли база данных хотя бы одну строку
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
                        String messageType = typeIdx >= 0 ? cursor.getString(typeIdx) : "TEXT";
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
                                filePath
                        );

                        messageDtoList.add(messageDto);

                    } while (cursor.moveToNext());
                }
            }
            android.util.Log.d("MailLis_DB", "Считано сообщений из SQLite для чата " + chatId + " (Группа: " + isGroup + "): " + messageDtoList.size());
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Критическая ошибка при чтении истории сообщений для чата: " + chatId, e);
            e.printStackTrace();
        }

        return messageDtoList;
    }

    /**
     * Моментально возвращает последнее сообщение чата.
     * Использует SQL-оптимизацию LIMIT 1, чтобы не фризить главный экран.
     */
    public MessageDto getLastMessages(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto lastMessageDto = null;

        // Запрашиваем строго ОДНУ строку (LIMIT 1), отсортированную от новых к старым (DESC)
        try (Cursor cursor = db.query(
                "messages",
                null,
                "chat_id = ?",
                new String[]{String.valueOf(chatId)},
                null, null,
                "timestamp DESC",
                "1" // Ультимативная оптимизация LIMIT 1
        )) {

            if (cursor.moveToFirst()) {
                // Узнаем индексы колонок строго ОДИН РАЗ до начала чтения
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int subjectIndex = cursor.getColumnIndex("subject");
                int isMineIndex = cursor.getColumnIndex("is_mine"); // Индекс новой колонки
                int statusIndex = cursor.getColumnIndex("is_sent");
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");


                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int status = cursor.getInt(statusIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);


                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Считываем готовое значение из БД: если в базе лежит 1, то это true, иначе false
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Собираем объект сообщения
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, isMine, serverUid, status, messageType, filePath);
                }
            }
        }
        // Если чат пустой, возвращаем дефолтный объект во избежание NullPointerException на главном экране
        return lastMessageDto != null
                ? lastMessageDto :
                new MessageDto(0, EMPTI_STRING, EMPTI_STRING, "Сообщений нет", 0L, false,
                        EMPTI_STRING, 0, EMPTI_STRING, EMPTI_STRING);
    }

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

            // =========================================================================
            // ШАГ 2: СБОРКА ДАННЫХ С УЧЕТОМ ОБНОВЛЕННОЙ СТРУКТУРЫ ТАБЛИЦЫ MESSAGES
            // =========================================================================
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
            values.put("message_type", "TEXT");
            values.put("file_path", (String) null);

            // ИСПРАВЛЕНИЕ: Записываем вычисленный реляционный флаг контекста (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // =========================================================================
            // ШАГ 3: ЗАПИСЬ В БАЗУ ДАННЫХ В ОБХОД ТАБЛИЦЫ CHATS
            // =========================================================================
            newId = db.insert("messages", null, values);

            android.util.Log.d("MailLis_DB", "Сообщение успешно добавлено. ID: " + newId
                    + " (Контекст: " + (isGroup ? "ГРУППА" : "ТЕТ-А-ТЕТ") + ")");

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Критическая ошибка при сохранении сообщения через saveGenericMessage", e);
            e.printStackTrace();
        }

        return (int) newId;
    }

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
    public int saveMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int generatedId = -1;

        try {
            // =========================================================================
            // ШАГ 1: ИНТЕЛЛЕКТУАЛЬНОЕ АВТО-ОПРЕДЕЛЕНИЕ ТИПА КОМНАТЫ
            // =========================================================================
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

            // =========================================================================
            // ШАГ 2: СБОРКА ДАННЫХ С УЧЕТОМ ОБНОВЛЕННОЙ СТРУКТУРЫ ТАБЛИЦЫ MESSAGES
            // =========================================================================
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
            values.put("message_type", "TEXT");
            values.put("file_path", (String) null); // Пустое поле для текста (заполнится только в методе фото)

            // АВТОМАТИЗАЦИЯ: Пишем вычисленный реляционный флаг контекста (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // =========================================================================
            // ШАГ 3: ЗАПИСЬ НА ДИСК
            // =========================================================================
            long rowId = db.insert("messages", null, values);
            generatedId = (int) rowId;

            android.util.Log.d("MailLis_DB_Save", "Исходящее сообщение успешно записано. Локальный ID: "
                    + generatedId + " (Контекст: " + (isGroup ? "ГРУППА" : "ТЕТ-А-ТЕТ") + ")");

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB_Save", "Критическая ошибка при локальном сохранении сообщения для чата " + chatId, e);
            e.printStackTrace();
        }

        return generatedId;
    }

    /**
     * Находит и возвращает конкретное сообщение из таблицы messages по его уникальному ID.
     *
     * @param localMessageId уникальный локальный идентификатор сообщения (id)
     * @return объект Message с заполненными данными или null, если сообщение не найдено
     */
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

                // Проверяем существование критически важных колонок
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int isSend = cursor.getInt(isSendIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);

                    // Безопасное чтение необязательных текстовых колонок
                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Упрощенное и безопасное считывание бита is_mine напрямую из базы
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Создаем объект сообщения
                    messageDto = new MessageDto(realId, sender, subject, text, time, isMine, serverUid, isSend, messageType, filePath);
                }
            }
        }

        return messageDto;
    }

    // 2. ДОБАВЛЯЕМ МЕТОД: Пометка сообщения как успешно отправленного
    public void markMessageAsSent(int localMessageId, String serverUid) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 1); // Успешно улетело!
        if (serverUid != null) {
            values.put("server_uid", serverUid);
        }
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
    }

    // Метод чтения активных чатов из SQLite
    public List<ChatPreview> loadChats() {
        List<ChatPreview> chatList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // СЛОЖНЫЙ СИНХРОННЫЙ UNION ЗАПРОС С АВТОПОДСТАНОВКОЙ ПОСЛЕДНЕГО СООБЩЕНИЯ
        String sql =
                // --- ЧАСТЬ 1: Вытягиваем одиночные чаты (is_group_message = 0) ---
                "SELECT " +
                        "  id AS chat_id, " +
                        "  recipient_email AS chat_name, " +
                        "  COALESCE((SELECT message_text FROM messages WHERE chat_id = chats.id AND is_group_message = 0 ORDER BY timestamp DESC LIMIT 1), 'Нет сообщений') AS last_msg, " +
                        "  COALESCE((SELECT timestamp FROM messages WHERE chat_id = chats.id AND is_group_message = 0 ORDER BY timestamp DESC LIMIT 1), 0) AS last_time, " +
                        "  0 AS is_group_flag " + // 0 означает false для одиночного чата
                        "FROM chats " +

                        "UNION ALL " + // Склеиваем таблицы без потери данных

                        // --- ЧАСТЬ 2: Вытягиваем групповые чаты (is_group_message = 1) ---
                        "SELECT " +
                        "  id AS chat_id, " +
                        "  group_name AS chat_name, " +
                        "  COALESCE((SELECT message_text FROM messages WHERE chat_id = group_chats.id AND is_group_message = 1 ORDER BY timestamp DESC LIMIT 1), 'Группа создана') AS last_msg, " +
                        "  COALESCE((SELECT timestamp FROM messages WHERE chat_id = group_chats.id AND is_group_message = 1 ORDER BY timestamp DESC LIMIT 1), 0) AS last_time, " +
                        "  1 AS is_group_flag " + // 1 означает true для группы
                        "FROM group_chats " +

                        // --- ОБЩАЯ СОРТИРОВКА: Новые чаты всегда сверху ---
                        "ORDER BY last_time DESC";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                // Запоминаем индексы колонок один раз
                int idIdx = cursor.getColumnIndex("chat_id");
                int nameIdx = cursor.getColumnIndex("chat_name");
                int msgIdx = cursor.getColumnIndex("last_msg");
                int timeIdx = cursor.getColumnIndex("last_time");
                int groupIdx = cursor.getColumnIndex("is_group_flag");

                if (idIdx >= 0 && nameIdx >= 0 && msgIdx >= 0 && timeIdx >= 0 && groupIdx >= 0) {
                    do {
                        int id = cursor.getInt(idIdx);
                        String name = cursor.getString(nameIdx);
                        //String lastMsg = cursor.getString(msgIdx);
                        long timestamp = cursor.getLong(timeIdx);
                        boolean isGroup = cursor.getInt(groupIdx) == 1;

                        // 2. БЕЗОПАСНАЯ ПРОВЕРКА НА РУКОПОЖАТИЕ
                        boolean isHandShake = false;

                        var lastMsg = getLastMessage(id, isGroup); // Получаем последнее сообщение чата

                        var msg = EMPTI_STRING;
                        // Проверяем на null: если сообщений нет, то это не handshake, а просто пустой чат
                        if (lastMsg != null && lastMsg.getSubject() != null) {
                            isHandShake = !lastMsg.getSubject().contains(MAILLIS_CHAT_MESSAGE);
                            msg = isHandShake ? "Чат создан" : EMPTI_STRING;
                        }

                        // Собираем готовый объект превью чата
                        var preview = new ChatPreview(id, name, msg, isHandShake, isGroup);
                        chatList.add(preview);

                    } while (cursor.moveToNext());
                }
            }
            android.util.Log.d("MailLis_DB_Union", "Собрано чатов для главного экрана: " + chatList.size());
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB_Union", "Критическая ошибка выполнения UNION запроса в loadChats()", e);
            e.printStackTrace();
        }

        return chatList;
    }

    /**
     * Извлекает из SQLite самое последнее сообщение для конкретного чата или группы.
     * Защищено от багов приведения типов (Type Mismatch) и смешивания контекстов.
     *
     * @param chatId  локальный идентификатор чата или группы
     * @param isGroup флаг типа комнаты: true - группа, false - одиночный чат
     * @return объект Message со всеми заполненными данными, либо null, если история пуста
     */
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
                    String messageType = typeIndex >= 0 ? cursor.getString(typeIndex) : "TEXT";
                    String filePath = pathIndex >= 0 ? cursor.getString(pathIndex) : null;
                    int isSent = cursor.getInt(isSentIndex);

                    // Собираем полноценный DTO-объект сообщения со всеми медиа-атрибутами
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, isMine, serverUid, isSent, messageType, filePath);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Ошибка извлечения последнего сообщения для чата " + chatId, e);
            e.printStackTrace();
        }

        return lastMessageDto;
    }

    /**
     * Сохраняет новый одиночный чат (Тет-а-Тет) в таблицу 'chats'.
     * Автоматически защищает от дублирования комнат с одним и тем же собеседником.
     *
     * @param title       отображаемое имя чата (обычно совпадает с email)
     * @param targetEmail почтовый адрес собеседника
     * @return уникальный ID чата (созданного или уже существующего), либо -1 в случае критической ошибки
     */
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
                    android.util.Log.d("MailLis_DB", "Чат с " + normalizedEmail + " уже существует. Возвращаем ID: " + chatId);
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

            android.util.Log.i("MailLis_DB", "Успешно создан новый одиночный чат. ID в SQLite: " + chatId + " для " + normalizedEmail);

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Критический сбой метода saveChat для email: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return chatId;
    }

    // Метод нативного чтения строк из таблицы SQLite
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

    // Нативное удаление строки из SQLite по ее ID
    public int deleteContact(int contactId) {
        // Запрос удаления: DELETE FROM contacts WHERE id = contactId
        return dbHelper.getWritableDatabase().delete("contacts", "id = ?", new String[]{String.valueOf(contactId)});
    }

    public int deleteContact(String email) {
        // Запрос удаления: DELETE FROM contacts WHERE email = email
        return dbHelper.getWritableDatabase().delete("contacts", "email = ?", new String[]{String.valueOf(email)});
    }

    /**
     * Находит и возвращает ID личного чата (Тет-а-Тет) по Email-адресу собеседника.
     * Ищет строго в таблице 'chats'. Защищено от багов разного регистра букв (Case-Insensitive).
     *
     * @param email почтовый адрес собеседника
     * @return уникальный ID чата из таблицы chats, либо -1, если чат еще не создан
     */
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
            android.util.Log.e("MailLis_DB", "Ошибка при поиске одиночного chatId по email: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return chatId;
    }

    @Override
    public int deleteMessage(int msdId) {
        return dbHelper.getWritableDatabase().delete("messages", "id = ?", new String[]{String.valueOf(msdId)});
    }

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
    public boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText,
                                       long currentTimestamp, String serverUid, String type, String imgPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSaved = false;

        try {
            // =========================================================================
            // ШАГ 1: АВТО-ОПРЕДЕЛЕНИЕ ТИПА ЧАТА (Одиночный или Групповой)
            // =========================================================================
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

            // =========================================================================
            // ШАГ 2: СБОРКА ДАННЫХ ДЛЯ ЗАПИСИ В ТАБЛИЦУ MESSAGES
            // =========================================================================
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
            values.put("message_type", type != null ? type : "TEXT");
            values.put("file_path", imgPath);

            // =========================================================================
            // ШАГ 3: БЕЗОПАСНАЯ АТОМАРНАЯ ВСТАВКА В БАЗУ ДАННЫХ
            // =========================================================================
            // Флаг CONFLICT_IGNORE защищает от двоения: если server_uid уже есть в SQLite,
            // запись просто пропустится, не ломая интерфейс чата и не дублируя сообщения.
            long rowId = db.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            isSaved = (rowId != -1);
            android.util.Log.d("MailLis_DB_Save", "Входящее сообщение обработано. ID в SQLite: " + rowId + " (Тип: " + (isGroup ? "ГРУППА" : "ОДИНОЧНЫЙ ЧАТ") + ")");

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB_Save", "Критический сбой сохранения входящего сообщения с UID: " + serverUid, e);
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

    /**
     * Извлекает публичный Tink Protobuf-ключ собеседника по локальному ID одиночного чата.
     * Использует реляционное объединение JOIN с изолированной таблицей-кэшем public_keys.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId локальный числовой идентификатор одиночного чата из таблицы chats
     * @return бинарный массив публичного ключа Tink, либо null, если ключ еще не был получен
     */
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
            android.util.Log.e("MailLis_DB", "Критическая ошибка при извлечении публичного ключа по chatId: " + chatId, e);
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

                // Проверяем существование критически важных для структуры полей
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0) {
                    do {
                        int realId = cursor.getInt(idIndex);
                        String sender = cursor.getString(senderIndex);
                        String text = cursor.getString(textIndex);
                        long time = cursor.getLong(timeIndex);
                        int status = cursor.getInt(statusIndex);
                        String messageType = cursor.getString(messageTypeIndex);
                        String filePath = cursor.getString(filePathIndex);

                        // Безопасное чтение необязательных текстовых колонок
                        String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                        String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                        // Упрощенное и безопасное считывание флага из базы
                        boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                        // Создаем чистый объект сообщения
                        MessageDto messageDto = new MessageDto(realId, sender, subject, text, time, isMine, serverUid, status, messageType, filePath);
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

    // 1. Добавить пользователя в бан-лист
    public boolean banUser(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long id = -1;
        try {
            ContentValues values = new ContentValues();
            values.put("email", email.trim().toLowerCase());
            values.put("timestamp", System.currentTimeMillis());

            // insertWithOnConflict защитит от дубликатов, если пользователя случайно забанят дважды
            id = db.insertWithOnConflict("ban_list", null, values, SQLiteDatabase.CONFLICT_IGNORE);

            // Опционально: при бане можно сразу пометить чат с ним как заблокированный или удалить его
        } catch (Exception e) {
            e.printStackTrace();
        }
        return id != -1;
    }

    // 2. Удалить пользователя из бан-листа (разбанить)
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

    // 3. КРИТИЧЕСКИЙ МЕТОД: Проверка, находится ли пользователь в бане
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

    /**
     * Полностью и безвозвратно удаляет чат и всю историю сообщений из SQLite базы данных.
     * Защищено от багов приведения типов (Type Mismatch) в современных Android SDK.
     *
     * @param chatId уникальный идентификатор удаляемого чата
     * @return true, если чат был успешно стерт из таблицы chats
     */
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

    /**
     * Переводит статус сообщения в состояние "Прочитано" (две галочки).
     */
    public void markMessageAsRead(String serverUid) {
        if (serverUid == null || serverUid.isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 2); // 2 = Две галочки
        db.update("messages", values, "server_uid = ?", new String[]{serverUid});

    }

    // Метод перевода сообщения в статус отправки (защита от повторной отправки параллельным потоком)
    public void markMessageAsSending(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 3); // 3 = Сообщение в процессе отправки прямо сейчас
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
    }

    public void resetMessageStatusToUnsent(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 0); // Возвращаем в "Часики"
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});

    }

    /**
     * Моментально возвращает общее количество сообщений, сохраненных в таблице messages.
     * Используется фоновой службой MailLisPushService для отслеживания появления новых входящих писем.
     *
     * @return общее число строк в таблице messages или 0 в случае пустоты или сбоя
     */
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

    /**
     * Извлекает из SQLite самое последнее прилетевшее входящее сообщение (is_mine = 0).
     * Используется фоновой службой MailLisPushService для формирования текста всплывающего Push-уведомления.
     *
     * @return объект Message с заполненными данными отправителя и текста, либо null, если входящих сообщений еще нет
     */
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

                // Проверяем, что все базовые колонки присутствуют в таблице
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int status = cursor.getInt(statusIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);

                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Собираем чистый объект сообщения
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, false, serverUid, status, messageType, filePath);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при получении последнего входящего сообщения для Push", e);
            e.printStackTrace();
        }

        return lastMessageDto;
    }

    /**
     * Обновляет тип сообщения и путь к локальному медиафайлу в SQLite.
     */
    public void updateMessageTypeAndPath(int messageId, String type, String path) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            ContentValues values = new ContentValues();
            values.put("message_type", type); // "IMAGE"
            values.put("file_path", path);   // путь к .jpg на диске
            db.update("messages", values, "id = CAST(? AS INTEGER)", new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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
    public long createGroupChat(String groupName, byte[] generatedGroupKey, String creatorEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long localId = -1;

        try {
            // 1. СБОРКА ДАННЫХ ДЛЯ НОВОЙ ТАБЛИЦЫ GROUP_CHATS
            ContentValues values = new ContentValues();
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

                android.util.Log.i("MailLis_DB_Group", "Успешно создана группа \"" + groupName
                        + "\" в таблице group_chats. Присвоен ID: " + localId);
            }

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB_Group", "Критическая ошибка при создании группы: " + groupName, e);
            e.printStackTrace();
        }

        return localId;
    }

    /**
     * Проверяет, является ли указанный идентификатор чата групповой комнатой.
     * Ищет строго в новой независимой таблице 'group_chats'.
     * Защищено от багов приведения типов данных (Type Mismatch) на Android 14+.
     *
     * @param chatId локальный числовой идентификатор чата или группы
     * @return true, если чат групповой; false, если чат одиночный (Тет-а-Тет)
     */
    public boolean isGroupChat(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean isGroup = false;

        // ОПТИМИЗАЦИЯ: Пишем чистый SQL-запрос. Нам достаточно проверить факт наличия
        // строки (SELECT 1), а оператор CAST(? AS INTEGER) гарантирует защиту от Type Mismatch.
        String sql = "SELECT 1 FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1";

        // Использование try-with-resources гарантирует автоматическое закрытие Cursor
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {
            // Если база данных вернула хотя бы одну строку — это 100% групповой чат
            if (cursor != null && cursor.moveToFirst()) {
                isGroup = true;
            }
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Ошибка при проверке типа чата для ID: " + chatId, e);
            e.printStackTrace();
        }

        return isGroup;
    }

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
    public void saveReceivedGroupChat(long remoteChatId, String incomingGroupName, byte[] decryptedGroupKey, String creatorEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        try {
            ContentValues values = new ContentValues();

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
                    SQLiteDatabase.CONFLICT_REPLACE
            );

            android.util.Log.i("MailLis_DB_Group", "Группа успешно импортирована в таблицу group_chats. Локальный ID: " + rowId + " (Remote ID: " + remoteChatId + ")");

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB_Group", "Критический сбой сохранения полученного группового чата. Remote ID: " + remoteChatId, e);
            e.printStackTrace();
        }
    }

    /**
     * Сохраняет или обновляет публичный ключ Tink для конкретного Email.
     * Работает как глобальный изолированный кэш ключей мессенджера.
     */
    public void savePublicKey(String email, byte[] publicKeyBytes) {
        if (email == null || publicKeyBytes == null) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            ContentValues values = new ContentValues();
            values.put("email", email.trim().toLowerCase());
            values.put("peer_public_key", publicKeyBytes);

            // CONFLICT_REPLACE гарантирует автоматический апдейт ключа при его изменении
            db.insertWithOnConflict("public_keys", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            android.util.Log.d("MailLis_Cache", "Ключ для " + email + " успешно сохранен в кэш public_keys");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Извлекает публичный ключ Tink из глобального кэша для любого Email.
     * Подходит как для одиночных чатов, так и для веерной рассылки в группах.
     */
    public byte[] getPublicKeyByEmail(String email) {
        if (email == null) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] key = null;
        String sql = "SELECT peer_public_key FROM public_keys WHERE email LIKE ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{email.trim().toLowerCase()})) {
            if (cursor != null && cursor.moveToFirst()) {
                key = cursor.getBlob(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return key;
    }

    /**
     * Создает новую групповую комнату локально (для Создателя группы).
     */
    public long createGroupChat(String groupName, String myEmail, byte[] generatedGroupKey) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long localId = -1;
        try {
            ContentValues values = new ContentValues();
            values.put("group_name", groupName.trim());
            values.put("creator_email", myEmail.trim().toLowerCase());
            values.put("group_secret_key", generatedGroupKey);

            // Вставляем запись и получаем локальный ID
            localId = db.insert("group_chats", null, values);

            // Для создателя remote_group_id изначально равен локальному ID
            if (localId != -1) {
                ContentValues updateValues = new ContentValues();
                updateValues.put("remote_group_id", localId);
                db.update("group_chats", updateValues, "id = ?", new String[]{String.valueOf(localId)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return localId;
    }

    /**
     * Безопасно сохраняет групповой чат на стороне Получателя инвайта.
     * Исключает дублирование благодаря UNIQUE ограничению на remote_group_id.
     */
    public void saveReceivedGroupChat(long remoteGroupId, String groupName, String creatorEmail, byte[] decryptedGroupKey) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            ContentValues values = new ContentValues();
            values.put("remote_group_id", remoteGroupId);
            values.put("group_name", groupName.trim());
            values.put("creator_email", creatorEmail.trim().toLowerCase());
            if (decryptedGroupKey != null) {
                values.put("group_secret_key", decryptedGroupKey);
            }

            db.insertWithOnConflict(
                    "group_chats",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
            android.util.Log.i("MailLis_DB", "Импортирована группа в отдельную таблицу group_chats. Remote ID: " + remoteGroupId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Безопасно добавляет нового участника в групповой чат.
     * Полностью адаптирован под актуальную колонку 'group_id'.
     * Защищен от дублирования записей внутри одной и той же группы.
     *
     * @param localGroupId локальный числовой ID группы из таблицы group_chats
     * @param userEmail    email-адрес добавляемого участника
     */
    public void addGroupMember(long localGroupId, String userEmail) {
        if (userEmail == null || userEmail.isEmpty()) {
            return;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Стандартизируем Email: очищаем пробелы и приводим к нижнему регистру
        String normalizedEmail = userEmail.trim().toLowerCase();

        try {
            // ЗАЩИТА: Проверяем, не добавлен ли уже этот пользователь в данную группу
            String checkSql = "SELECT 1 FROM group_members WHERE group_id = CAST(? AS INTEGER) AND user_email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{String.valueOf(localGroupId), normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    android.util.Log.d("MailLis_DB", "Пользователь " + normalizedEmail + " уже состоит в группе " + localGroupId);
                    return; // Прерываем выполнение, чтобы не плодить дубликаты
                }
            }

            // СБОРКА ДАННЫХ ДЛЯ ВСТАВКИ
            ContentValues values = new ContentValues();
            // ИСПРАВЛЕНИЕ: Пишем данные строго в новую колонку group_id
            values.put("group_id", localGroupId);
            values.put("user_email", normalizedEmail);

            // Вставляем запись в таблицу group_members
            long rowId = db.insert("group_members", null, values);

            android.util.Log.i("MailLis_DB", "В группу " + localGroupId + " успешно добавлен участник: " + normalizedEmail + " (Строка: " + rowId + ")");

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Ошибка при добавлении участника " + normalizedEmail + " в группу " + localGroupId, e);
            e.printStackTrace();
        }
    }

    /**
     * Возвращает список Email-адресов всех участников конкретной группы (исключая свой собственный).
     * Полностью адаптирован под новую структуру таблицы group_members (колонка group_id).
     *
     * @param localGroupId локальный числовой ID группы из таблицы group_chats
     * @param myEmail      email текущего пользователя приложения (чтобы исключить себя из рассылки)
     * @return список email-адресов участников группы
     */
    public List<String> getGroupMembers(long localGroupId, String myEmail) {
        List<String> members = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ИСПРАВЛЕНИЕ: Выбираем user_email, фильтруя по новой колонке group_id
        String sql = "SELECT user_email FROM group_members WHERE group_id = CAST(? AS INTEGER) AND user_email != ?";

        String normalizedMyEmail = myEmail != null ? myEmail.trim().toLowerCase() : "";

        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(localGroupId), normalizedMyEmail})) {
            // Проверяем, вернула ли база данных строки
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String memberEmail = cursor.getString(0);
                    if (memberEmail != null) {
                        members.add(memberEmail.trim().toLowerCase());
                    }
                } while (cursor.moveToNext());
            }
            android.util.Log.d("MailLis_DB", "Для группы " + localGroupId + " найдено участников: " + members.size());
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Ошибка при получении списка участников группы " + localGroupId, e);
            e.printStackTrace();
        }

        return members;
    }

    /**
     * Извлекает секретный AES-ключ группы по её локальному ID.
     */
    public byte[] getGroupSecretKey(long localGroupId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] key = null;
        String sql = "SELECT group_secret_key FROM group_chats WHERE id = CAST(? AS INTEGER)";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(localGroupId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                key = cursor.getBlob(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return key;
    }

    /**
     * Возвращает название группы по её локальному ID.
     */
    public String getGroupNameById(long localGroupId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String name = "Групповой чат";
        String sql = "SELECT group_name FROM group_chats WHERE id = CAST(? AS INTEGER)";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(localGroupId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return name;
    }

    /**
     * Безвозвратно удаляет публичный ключ Tink собеседника из глобального кэша базы данных.
     * Вызывается сразу после успешного удаления одиночного или группового чата для очистки крипто-следов.
     *
     * @param email почтовый адрес пользователя, чей ключ необходимо стереть
     * @return true, если строка с ключом была успешно найдена и удалена; false в противном случае
     */
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
            android.util.Log.d("MailLis_Crypto", "Удаление ключа для " + normalizedEmail + ". Очищено строк кэша: " + rowsAffected);

        } catch (Exception e) {
            android.util.Log.e("MailLis_Crypto", "Критическая ошибка при удалении публичного ключа для: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return isDeleted;
    }

    /**
     * Полностью и безвозвратно удаляет групповой чат, список его участников и историю переписки.
     * Использует прямые SQL-запросы для исключения багов приведения типов на Android 14+.
     *
     * @param localGroupId локальный числовой ID группы из таблицы group_chats
     * @return true, если транзакция удаления завершилась успешно; false в случае сбоя
     */
    public boolean deleteGroupChatAndMessages(long localGroupId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success = false;

        // Открываем атомарную транзакцию (все удаления выполнятся строго вместе)
        db.beginTransaction();
        try {
            // 1. Стираем все сообщения, привязанные к этой группе (флаг is_group_message = 1)
            // Передаем аргументы в массив Object[], чтобы SQLite получил число, а не текст!
            db.execSQL("DELETE FROM messages WHERE chat_id = ? AND is_group_message = 1",
                    new Object[]{localGroupId});
            android.util.Log.d("MailLis_DB_Del", "История сообщений группы " + localGroupId + " успешно стерта.");

            // 2. Стираем саму комнату группы из таблицы group_chats
            // Благодаря ON DELETE CASCADE в структуре БД, участники из таблицы group_members удалятся АВТОМАТИЧЕСКИ!
            db.execSQL("DELETE FROM group_chats WHERE id = ?",
                    new Object[]{localGroupId});
            android.util.Log.d("MailLis_DB_Del", "Комната группы " + localGroupId + " удалена из group_chats.");

            // Если дошли сюда без исключений — фиксируем успех транзакции в SQLite
            db.setTransactionSuccessful();
            success = true;

            android.util.Log.i("MailLis_DB_Del", "Групповой чат " + localGroupId + " полностью очищен из базы данных.");

        } catch (Exception e) {
            android.util.Log.e("MailLis_DB_Del", "Критическая ошибка транзакции при удалении группы " + localGroupId, e);
            e.printStackTrace();
            success = false;
        } finally {
            // Обязательно закрываем транзакцию, возвращая базу в пул многопоточности WAL
            db.endTransaction();
        }

        return success;
    }

    /**
     * Находит и возвращает Email собеседника (получателя) по уникальному ID одиночного чата.
     * Ищет строго в таблице 'chats' с защитой от багов приведения типов на Android 14+.
     *
     * @param chatId уникальный локальный числовой идентификатор чата из таблицы chats
     * @return email адрес собеседника или пустая строка (EMPTI_STRING), если чат не найден
     */
    public String getRecipientEmailByChatId(long chatId) {
        String recipientEmail = ""; // Используйте вашу константу EMPTI_STRING, если она объявлена
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
            android.util.Log.e("MailLis_DB", "Ошибка при поиске recipient_email по одиночному chatId: " + chatId, e);
            e.printStackTrace();
        }

        return recipientEmail;
    }
}
