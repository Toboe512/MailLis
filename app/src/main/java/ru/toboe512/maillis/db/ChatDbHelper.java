package ru.toboe512.maillis.db;


import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ChatDbHelper extends SQLiteOpenHelper {

    // Имя файла базы данных на устройстве и версия
    private static final String DATABASE_NAME = "MailLisChat.db";
    private static final int DATABASE_VERSION = 19;

    public ChatDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        // ВОТ ЭТА СТРОКА разрешает параллельные операции чтения и записи из разных потоков!
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // 1. ВКЛЮЧАЕМ МНОГОПОТОЧНЫЙ РЕЖИМ WAL (Параллельное чтение и запись)
        db.enableWriteAheadLogging();

        // 2. Включаем поддержку внешних ключей для каскадного удаления мемберов
        if (!db.isReadOnly()) {
            db.execSQL("PRAGMA foreign_keys = ON;");
        }
    }

    // Вызывается один раз при самом первом создании базы данных на телефоне
    @Override
    public void onCreate(SQLiteDatabase db) {
        // 1. Таблица контактов (Личная записная книжка пользователя)
        db.execSQL("CREATE TABLE contacts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT, " +
                "email TEXT UNIQUE" +
                ");");

        // 2. Таблица одиночных чатов (Тет-а-Тет). Отвечает строго за приватные диалоги
        db.execSQL("CREATE TABLE chats (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT, " +
                "recipient_email TEXT UNIQUE" + // UNIQUE защищает от создания двух одинаковых чатов с одним другом
                ");");

        // 3. Таблица для групповых комнат (Вынесена в отдельную независимую сущность)
        db.execSQL("CREATE TABLE group_chats (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "remote_group_id INTEGER UNIQUE, " + // ID группы от создателя для P2P-синхронизации
                "group_name TEXT, " +
                "creator_email TEXT, " +
                "group_secret_key BLOB" +            // Симметричный AES-ключ группы
                ");");

        // 4. Таблица участников групп (ИСПРАВЛЕНО: Создается ПОСЛЕ group_chats и ссылается на правильное поле group_id)
        db.execSQL("CREATE TABLE group_members (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "group_id INTEGER, " +               // Локальный ID группы из таблицы group_chats
                "user_email TEXT, " +
                "FOREIGN KEY(group_id) REFERENCES group_chats(id) ON DELETE CASCADE" +
                ");");

        // 5. Глобальная таблица-кэш для ВСЕХ публичных ключей Tink (Контакты и группы остаются чистыми от мусора)
        db.execSQL("CREATE TABLE public_keys (" +
                "email TEXT PRIMARY KEY, " +         // Email является уникальным ключом
                "peer_public_key BLOB NOT NULL" +
                ");");

        // 6. Универсальная таблица сообщений (Связана с chats или group_chats через флаг типа)
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER, " +                // Сюда пишется либо id из chats, либо id из group_chats
                "sender_email TEXT, " +
                "message_text TEXT, " +
                "subject TEXT, " +
                "timestamp INTEGER, " +
                "server_uid TEXT UNIQUE, " +         // Уникальный Message-ID для защиты от дубликатов пишек
                "is_sent INTEGER DEFAULT 0, " +      // 1 = Отправлено, 0 = Ждет сеть (ретрай), 3 = В процессе SMTP
                "is_mine INTEGER DEFAULT 0, " +      // 1 = Моё, 0 = Чужое
                "message_type TEXT DEFAULT 'TEXT', " + // 'TEXT' или 'IMAGE'
                "is_group_message INTEGER DEFAULT 0, " + // 0 = Личное сообщение, 1 = Групповое
                "file_path TEXT" +                   // Абсолютный путь к зашифрованному .jpg на диске (для фото)
                ");");

        // 7. Таблица забаненных пользователей
        db.execSQL("CREATE TABLE ban_list (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "email TEXT UNIQUE, " +
                "timestamp INTEGER" +
                ");");
    }

    // Вызывается при обновлении версии приложения (если структура таблиц поменяется)
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("DROP TABLE IF EXISTS chats");
        db.execSQL("DROP TABLE IF EXISTS contacts");
        db.execSQL("DROP TABLE IF EXISTS ban_list");
        db.execSQL("DROP TABLE IF EXISTS group_members");
        db.execSQL("DROP TABLE IF EXISTS public_keys");
        db.execSQL("DROP TABLE IF EXISTS group_chats");
        onCreate(db);
    }
}