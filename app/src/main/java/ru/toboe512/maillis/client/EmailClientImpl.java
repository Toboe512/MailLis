package ru.toboe512.maillis.client;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.mail.Authenticator;
import jakarta.mail.Folder;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import ru.toboe512.maillis.activity.ChatActivity;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.Message;

public class EmailClientImpl implements EmailClient {

    // Переменные для работы с базой данных
    private ChatDbHelper dbHelper;

    private DBService dBServiceSQLite;

    public EmailClientImpl(ChatDbHelper dbHelper, DBService dBServiceSQLite) {
        this.dbHelper = dbHelper;
        this.dBServiceSQLite = dBServiceSQLite;
    }

//    // Сетевая SMTP отправка на фоновом потоке
//    public void sendEmailOverNetwork(String recipientEmail, final String messageText, final int localMessageId, SharedPreferences prefs) {
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    final String username = prefs.getString("email_login", "");
//                    final String password = prefs.getString("email_password", "");
//                    String smtpServer = prefs.getString("smtp_server", "smtp.mail.ru");
//                    String smtpPort = prefs.getString("smtp_port", "465");
//
//                    if (username.isEmpty() || password.isEmpty()) return;
//
//                    Properties props = new Properties();
//                    props.put("mail.smtp.host", smtpServer);
//                    props.put("mail.smtp.socketFactory.port", smtpPort);
//                    props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
//                    props.put("mail.smtp.auth", "true");
//                    props.put("mail.smtp.port", smtpPort);
//
//                    Session session = Session.getInstance(props, new Authenticator() {
//                        @Override
//                        protected PasswordAuthentication getPasswordAuthentication() {
//                            return new PasswordAuthentication(username, password);
//                        }
//                    });
//
//                    MimeMessage message = new MimeMessage(session);
//                    message.setFrom(new InternetAddress(username));
//                    message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
//                    message.setSubject("MailLis_Chat_Message");
//                    message.setText(messageText);
//                    // 1. Отправляем в сеть через SMTP
//                    Transport.send(message);
//                    runOnUiThread(() -> Toast.makeText(EmailClientImpl.this, "Синхронизировано со службой SMTP!", Toast.LENGTH_SHORT).show());
//
//
//                    // 2. ИЗВЛЕКАЕМ СГЕНЕРИРОВАННЫЙ СЕРВЕРОМ ID ПИСЬМА
//                    String[] mimeIds = message.getHeader("Message-ID");
//                    String generatedServerUid = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;
//
//                    // 3. Если сервер вернул ID, мгновенно записываем его в SQLite для этой строки
//                    if (generatedServerUid != null) {
//                        SQLiteDatabase db = dbHelper.getWritableDatabase();
//                        ContentValues values = new ContentValues();
//                        values.put("server_uid", generatedServerUid);
//
//                        // Обновляем строку, где id равен нашему локальному сохраненному ID
//                        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
//                        db.close();
//
//                        // Дополнительно обновляем ID в текущем массиве памяти, чтобы сообщение можно было удалить без перезахода
//                        for (ru.toboe512.maillis.dto.Message m : messageList) {
//                            // Находим наше сообщение по локальному ID и вставляем полученный серверный UID
//                            if (m.getId() == localMessageId) {
//                                // Прямая перезапись поля в памяти (так как мы добавили serverUid в модель, обновим объект)
//                                // Пересоздавать не обязательно, но для чистоты обновим историю из базы на следующем цикле или запишем напрямую
//                                loadMessagesFromDatabase(); // Перечитываем базу для синхронизации ID
//                                break;
//                            }
//                        }
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();
//    }

    // Фоновый поток для работы с IMAP
    public List<Message>  fetchIncomingEmailsOverNetwork(int chatId, String myEmail, SharedPreferences prefs) {

        // 1. Создаем пул потоков (в данном случае из одного потока)
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 2. Определяем задачу Callable, которая возвращает строку после паузы
        Callable<List<Message>> task = () -> {
            List<Message> messageList = new ArrayList<>();
            try {
                final String username = prefs.getString("email_login", "");
                final String password = prefs.getString("email_password", "");
                String imapServer = prefs.getString("imap_server", "imap.mail.ru");
                String imapPort = prefs.getString("imap_port", "993");

                if (username.isEmpty() || password.isEmpty()) return new ArrayList<>();

                Properties props = new Properties();
                props.put("mail.store.protocol", "imaps");
                props.put("mail.imaps.host", imapServer);
                props.put("mail.imaps.port", imapPort);
                props.put("mail.imaps.timeout", "15000");
                props.put("mail.imaps.connectiontimeout", "15000");
                // ОПТИМИЗАЦИЯ: Отключаем прогрузку лишних данных, скачиваем только текст
                props.put("mail.imaps.partialfetch", "false");
                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ ДЛЯ РЕАЛЬНЫХ УСТРОЙСТВ:
                // Принудительно доверяем серверу imap.mail.ru и включаем фабрику безопасных сокетов SSL
                props.put("mail.imaps.ssl.trust", "*");
                props.put("mail.imaps.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.imaps.socketFactory.fallback", "false");
                props.put("mail.imaps.socketFactory.port", imapPort);

                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Принудительно включаем шифрование TLSv1.2 и TLSv1.3 для обхода блокировок Android 10+
                props.put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3");

                // Если порт изменен на 143, включаем STARTTLS
                if ("143".equals(imapPort)) {
                    props.put("mail.imaps.starttls.enable", "true");
                    props.put("mail.imaps.starttls.required", "true");
                }


                Session session = Session.getInstance(props, null);
                Store store = session.getStore("imaps");
                store.connect(imapServer, username, password);

                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);

                int messageCount = inbox.getMessageCount();
                if (messageCount <= 0) return new ArrayList<>();

                // Сканируем последние 25 писем, чтобы точно не пропустить ответы
                int startMessage = Math.max(1, messageCount - 25);
                jakarta.mail.Message[] messages = inbox.getMessages(startMessage, messageCount);
                boolean hasNewData = false;


                SQLiteDatabase db = dbHelper.getWritableDatabase();

                for (jakarta.mail.Message msg : messages) {
                    if (msg.getSubject() != null && msg.getSubject().equals("MailLis_Chat_Message")) {
//TODO
                        String sender = ((InternetAddress) msg.getFrom()[0]).getAddress();
                        String text = msg.getContent().toString();
                        long time = msg.getSentDate().getTime();

                        String[] mimeIds = msg.getHeader("Message-ID");

                        // ИСПРАВЛЕНО: Безопасное извлечение строки из массива
                        String serverUid;
                        if (mimeIds != null && mimeIds.length > 0 && mimeIds[0] != null) {
                            serverUid = mimeIds[0];
                        } else {
                            serverUid = String.valueOf(time);
                        }

                        Cursor c = db.rawQuery("SELECT id FROM messages WHERE server_uid = ?", new String[]{serverUid});
                        if (c != null) {
                            if (c.getCount() == 0) {
                                ContentValues values = new ContentValues();
                                values.put("chat_id", chatId);
                                values.put("sender_email", sender);
                                values.put("message_text", text);
                                values.put("timestamp", time);
                                values.put("server_uid", serverUid);
                                db.insert("messages", null, values);

                                hasNewData = true;
                            }
                            c.close();
                        }
                    }
                }

                db.close();
                inbox.close(false);
                store.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
            return messageList;
        };

        // 3. Отправляем задачу в пул и получаем объект Future
        Future<List<Message>> future = executor.submit(task);
        List<Message> result = new ArrayList<>();
        try {
            // 4. Метод get() блокирует текущий поток и ждет завершения задачи
            result = future.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } finally {
            // 5. Обязательно закрываем пул потоков
            executor.shutdown();
        }

        return result;
    }


    // Сетевая SMTP отправка на фоновом потоке
    public List<Message> sendEmailOverNetwork(int chatId, String myEmail, String recipientEmail, final String messageText, final int localMessageId, SharedPreferences prefs) {
        // 1. Создаем пул потоков (в данном случае из одного потока)
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 2. Определяем задачу Callable, которая возвращает строку после паузы
        Callable<List<Message>> task = () -> {
            List<Message> messageList = new ArrayList<>();
            try {
                final String username = prefs.getString("email_login", "");
                final String password = prefs.getString("email_password", "");
                String smtpServer = prefs.getString("smtp_server", "smtp.mail.ru");
                String smtpPort = prefs.getString("smtp_port", "465");

                if (username.isEmpty() || password.isEmpty()) return new ArrayList<>();

                Properties props = new Properties();
                props.put("mail.smtp.host", smtpServer);
                props.put("mail.smtp.socketFactory.port", smtpPort);
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.port", smtpPort);

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });

                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(username));
                message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
                message.setSubject("MailLis_Chat_Message");
                message.setText(messageText);
                // 1. Отправляем в сеть через SMTP
                Transport.send(message);
                //runOnUiThread(() -> Toast.makeText(context, "Синхронизировано со службой SMTP!", Toast.LENGTH_SHORT).show());


                // 2. ИЗВЛЕКАЕМ СГЕНЕРИРОВАННЫЙ СЕРВЕРОМ ID ПИСЬМА
                String[] mimeIds = message.getHeader("Message-ID");
                String generatedServerUid = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;

                // 3. Если сервер вернул ID, мгновенно записываем его в SQLite для этой строки
                if (generatedServerUid != null) {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    ContentValues values = new ContentValues();
                    values.put("server_uid", generatedServerUid);

                    // Обновляем строку, где id равен нашему локальному сохраненному ID
                    db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
                    db.close();

                    // Дополнительно обновляем ID в текущем массиве памяти, чтобы сообщение можно было удалить без перезахода
                    for (ru.toboe512.maillis.dto.Message m : messageList) {
                        // Находим наше сообщение по локальному ID и вставляем полученный серверный UID
                        if (m.getId() == localMessageId) {
                            // Прямая перезапись поля в памяти (так как мы добавили serverUid в модель, обновим объект)
                            // Пересоздавать не обязательно, но для чистоты обновим историю из базы на следующем цикле или запишем напрямую
                            messageList.addAll(dBServiceSQLite.loadMessagesFromDatabase(chatId, myEmail));// Перечитываем базу для синхронизации ID

                            break;
                        }
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
            return messageList;
        };


        // 3. Отправляем задачу в пул и получаем объект Future
        Future<List<Message>> future = executor.submit(task);
        List<Message> result = new ArrayList<>();
        try {
            // 4. Метод get() блокирует текущий поток и ждет завершения задачи
            result = future.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } finally {
            // 5. Обязательно закрываем пул потоков
            executor.shutdown();
        }

        return result;
    }


    // Сетевая SMTP отправка на фоновом потоке
//    public static void sendEmailOverNetwork(final String messageText, final int localMessageId, prefs SharedPreferences) {
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//
//                    final String username = prefs.getString("email_login", "");
//                    final String password = prefs.getString("email_password", "");
//                    String smtpServer = prefs.getString("smtp_server", "smtp.mail.ru");
//                    String smtpPort = prefs.getString("smtp_port", "465");
//
//                    if (username.isEmpty() || password.isEmpty()) return;
//
//                    Properties props = new Properties();
//                    props.put("mail.smtp.host", smtpServer);
//                    props.put("mail.smtp.socketFactory.port", smtpPort);
//                    props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
//                    props.put("mail.smtp.auth", "true");
//                    props.put("mail.smtp.port", smtpPort);
//
//                    Session session = Session.getInstance(props, new Authenticator() {
//                        @Override
//                        protected PasswordAuthentication getPasswordAuthentication() {
//                            return new PasswordAuthentication(username, password);
//                        }
//                    });
//
//                    MimeMessage message = new MimeMessage(session);
//                    message.setFrom(new InternetAddress(username));
//                    message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
//                    message.setSubject("MailLis_Chat_Message");
//                    message.setText(messageText);
//                    // 1. Отправляем в сеть через SMTP
//                    Transport.send(message);
//                    runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Синхронизировано со службой SMTP!", Toast.LENGTH_SHORT).show());
//
//
//                    // 2. ИЗВЛЕКАЕМ СГЕНЕРИРОВАННЫЙ СЕРВЕРОМ ID ПИСЬМА
//                    String[] mimeIds = message.getHeader("Message-ID");
//                    String generatedServerUid = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;
//
//                    // 3. Если сервер вернул ID, мгновенно записываем его в SQLite для этой строки
//                    if (generatedServerUid != null) {
//                        SQLiteDatabase db = dbHelper.getWritableDatabase();
//                        ContentValues values = new ContentValues();
//                        values.put("server_uid", generatedServerUid);
//
//                        // Обновляем строку, где id равен нашему локальному сохраненному ID
//                        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
//                        db.close();
//
//                        // Дополнительно обновляем ID в текущем массиве памяти, чтобы сообщение можно было удалить без перезахода
//                        for (ru.toboe512.maillis.dto.Message m : messageList) {
//                            // Находим наше сообщение по локальному ID и вставляем полученный серверный UID
//                            if (m.getId() == localMessageId) {
//                                // Прямая перезапись поля в памяти (так как мы добавили serverUid в модель, обновим объект)
//                                // Пересоздавать не обязательно, но для чистоты обновим историю из базы на следующем цикле или запишем напрямую
//                                loadMessagesFromDatabase(); // Перечитываем базу для синхронизации ID
//                                break;
//                            }
//                        }
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();
//    }
}
