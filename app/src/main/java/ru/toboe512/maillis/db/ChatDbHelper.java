package ru.toboe512.maillis.db;


import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ChatDbHelper extends SQLiteOpenHelper {

    // Имя файла базы данных на устройстве и версия
    private static final String DATABASE_NAME = "MailLisChat.db";
    private static final int DATABASE_VERSION = 15;

    // Конструктор
    public ChatDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        // ВОТ ЭТА СТРОКА разрешает параллельные операции чтения и записи из разных потоков!
        setWriteAheadLoggingEnabled(true);
    }

    // Вызывается один раз при самом первом создании базы данных на телефоне
    @Override
    public void onCreate(SQLiteDatabase db) {
        // 1. Таблица контактов
        db.execSQL("CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, email TEXT UNIQUE);");
        // 2. Таблица чатов (личные и групповые)
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, type TEXT, " +
                "recipient_email TEXT, last_message TEXT, timestamp INTEGER, shared_secret BLOB);");

        // 3. Таблица сообщений (связана с таблицей чатов)
        // ОБНОВЛЕНО: Добавлена колонка server_uid с флагом UNIQUE
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER, " +
                "sender_email TEXT, " +
                "message_text TEXT, " +
                "subject TEXT, " +
                "timestamp INTEGER, " +
                "server_uid TEXT UNIQUE," + // Уникальный ID письма с сервера
                "is_sent INTEGER DEFAULT 0," + // 1 = Отправлено, 0 = Ожидает отправки (сеть упала) 3 - взято в отправку
                "is_mine INTEGER DEFAULT 0" + //  1 = моё, 0 = чужое
                ");");

        db.execSQL("CREATE TABLE ban_list (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "email TEXT UNIQUE, " + // Email забаненного пользователя (должен быть уникальным)
                "timestamp INTEGER" +  // Время блокировки
                ");");
    }

    // Вызывается при обновлении версии приложения (если структура таблиц поменяется)
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("DROP TABLE IF EXISTS chats");
        db.execSQL("DROP TABLE IF EXISTS contacts");
        db.execSQL("DROP TABLE IF EXISTS ban_list");
        onCreate(db);
    }
}