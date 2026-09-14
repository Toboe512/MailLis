package ru.toboe512.maillis.client;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.Message;

public class EmailClientImpl implements EmailClient {

    // Переменные для работы с базой данных
    private ChatDbHelper dbHelper;

    private DBService dbService;

    public EmailClientImpl(ChatDbHelper dbHelper, DBService dbService, Context context) {
        this.dbHelper = dbHelper;
        this.dbService = dbService;
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
    public List<Message> fetchIncomingEmailsOverNetwork(long chatId, String myEmail, Context context, SharedPreferences prefs) {

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
                    if (msg.getSubject() != null && msg.getSubject().equals("MailLis_Chat_Message")
                            && !msg.getSubject().contains("[MailLis-Key-Request]")
                            && !msg.getSubject().contains("[MailLis-Key-Response]")) {
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
//TODO Вынести в класс базыданных
                        Cursor c = db.rawQuery("SELECT id FROM messages WHERE server_uid = ?", new String[]{serverUid});
                        if (c != null) {
                            if (c.getCount() == 0) {
                                ContentValues values = new ContentValues();
                                values.put("chat_id", chatId);
                                values.put("sender_email", sender);
                                values.put("subject", msg.getSubject());
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

    @Override
    public List<Message> fetchIncomingHandshakeOverNetwork(Context context, SharedPreferences prefs) {
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

                for (jakarta.mail.Message msg : messages) {
                    if (msg.getSubject() != null) {
                        String sender = ((InternetAddress) msg.getFrom()[0]).getAddress();
                        long chatId = 0;

                        if (msg.getSubject().contains("[MailLis-Key-Request]")) {
                            chatId = dbService.saveChat(sender, sender);
                        }
                        checkIncomingEmailForHandshake(sender, msg.getSubject().toString(), msg.getContent().toString(), context, 0, prefs);
                        if (msg.getSubject().contains("[MailLis-Key-Response]") || msg.getSubject().contains("[MailLis-Key-Request]")) {

                            UIDFolder uidFolder = (UIDFolder) inbox;
                            // ВОТ ЗДЕСЬ ДОСТАЕМ СЕРВЕРНЫЙ UID (возвращает long)
                            long uid = uidFolder.getUID(msg);

                            // Переводим его в String, так как в вашей бд и DTO используется строка (serverUid)
                            String serverUid = String.valueOf(uid);
                            deleteMessageFromMailRuServer(serverUid, context);
                        }
                    }

                }
                inbox.close(false);
                store.close();

            } catch (Exception e) {
                //TODO логировааниее
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

    public interface OnMessagesSynchronizedListener {
        // Этот метод сработает, когда отправка завершится и база обновится
        void onSynchronized(List<Message> updatedMessages);

        void onError(Exception e);
    }

    // Сетевая SMTP отправка на фоновом потоке
    public void sendEmailOverNetwork(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, OnMessagesSynchronizedListener listener) {

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // Handler нужен, чтобы вернуть результат в Главный (UI) поток Android
            Handler mainHandler = new Handler(Looper.getMainLooper());

            executor.execute(() -> {
                try {
                    final String username = prefs.getString("email_login", "");
                    final String password = prefs.getString("email_password", "");
                    String smtpServer = prefs.getString("smtp_server", "smtp.mail.ru");
                    String smtpPort = prefs.getString("smtp_port", "465");

                    if (username.isEmpty() || password.isEmpty()) {
                        if (listener != null)
                            mainHandler.post(() -> listener.onError(new Exception("Пустой логин/пароль")));
                        return;
                    }

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
                    message.setSubject(subject);
                    message.setText(messageText);

                    // 1. ПРИНУДИТЕЛЬНО ПРИМЕНЯЕМ ИЗМЕНЕНИЯ, чтобы сгенерировался Message-ID
                    message.saveChanges();

                    // 2. Отправляем в сеть
                    Transport.send(message);

                    // 3. БЕЗОПАСНО ИЗВЛЕКАЕМ Message-ID
                    String generatedServerUid = message.getMessageID(); // Используем встроенный метод вместо getHeader()

                    if (generatedServerUid != null) {
                        SQLiteDatabase db = dbHelper.getWritableDatabase();
                        ContentValues values = new ContentValues();
                        values.put("server_uid", generatedServerUid);
                        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
                        db.close();
                    }


                    // 4. Проверяем, что сервис базы данных существует перед вызовом
                    if (dbService != null) {
                        String myEmail = prefs.getString("email_login", "my@mail.ru");
                        // Передаем (int) chatId, если ваша БД принимает инты
                        List<Message> freshlyLoadedMessages = dbService.getMessages((int) chatId, myEmail);

                        if (listener != null) {
                            mainHandler.post(() -> listener.onSynchronized(freshlyLoadedMessages));
                        }
                    } else {
                        if (listener != null) {
                            mainHandler.post(() -> listener.onError(new Exception("dBServiceSQLite не инициализирован!")));
                        }
                    }

                } catch (Exception e) {
                    //TODO логировааниее
                    e.printStackTrace();
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель
                    }
                } finally {
                    executor.shutdown();
                }
            });
        }
    }


    public byte[] sendHandshakeRequest(String targetEmail, Context context, long chatId, final int localMessageId, SharedPreferences prefs) {
        byte[] myPublicKeyBytes = null;
        try {
            CryptoService cryptoService = new CryptoServiceImpl(context, String.valueOf(chatId));
            // 1. Получаем байты своего публичного ключа и переводим в Base64 строку
            myPublicKeyBytes = cryptoService.getPublicKeyToBytes();
            String myPublicKeyBase64 = Base64.encodeToString(myPublicKeyBytes, Base64.NO_WRAP);

            // Отправляем письмо по SMTP
            // Тело письма (body) содержит наш ключ.
            // Метод sendRawEmailOverNetwork — это ваш метод отправки SMTP
            String myEmail = prefs.getString("email_login", "my@mail.ru");
            List<Message> messageList = new ArrayList<>();

            sendEmailOverNetwork(chatId, targetEmail, "[MailLis-Key-Request]", myPublicKeyBase64,
                    localMessageId, prefs, new OnMessagesSynchronizedListener() {

                @Override
                public void onSynchronized(List<Message> updatedMessages) {
                    messageList.addAll(updatedMessages);
                }

                @Override
                public void onError(Exception e) {
                    // Обработка ошибки сети или авторизации
                    System.out.println("Ошибка при отправке: " + e.getMessage());
                }
            });

            System.out.println("Запрос рукопожатия отправлен на " + targetEmail);
        } catch (Exception e) {
            //TODO логировааниее
            e.printStackTrace();
        }

        return myPublicKeyBytes;
    }

    public String sendHandshakeResponse(String targetEmail, Context context, long chatId, final int localMessageId, SharedPreferences prefs) {
        List<Message> messages = new ArrayList<>();
        try {
            CryptoService cryptoService = new CryptoServiceImpl(context, Long.valueOf(chatId).toString());
            byte[] myPublicKeyBytes = cryptoService.getPublicKeyToBytes();
            String myPublicKeyBase64 = Base64.encodeToString(myPublicKeyBytes, Base64.NO_WRAP);
            // Отправляем свой ключ в ответ
            sendEmailOverNetwork(chatId, targetEmail, "[MailLis-Key-Response]", myPublicKeyBase64, localMessageId, prefs,
                    new OnMessagesSynchronizedListener() {

                @Override
                public void onSynchronized(List<Message> updatedMessages) {
                    messages.addAll(updatedMessages);
                }

                @Override
                public void onError(Exception e) {
                    // Обработка ошибки сети или авторизации
                    System.out.println("Ошибка при отправке: " + e.getMessage());
                }
            });
            System.out.println("Ответный ключ отправлен на " + targetEmail);


        } catch (Exception e) {
            //TODO логировааниее
            e.printStackTrace();
        }
        return messages.get(0).getServerUid();
    }

    // Модифицируем логику разбора входящих писем
    private boolean checkIncomingEmailForHandshake(String senderEmail, String subject, String body, Context context, final int localMessageId, SharedPreferences prefs) {
        if (subject == null) return false;
        String myEmail = prefs.getString("email_login", "my@mail.ru");
        if (subject.contains("[MailLis-Key-Request]")) {
            // СЛУЧАЙ 1: Нам прислали ключ и просят наш в ответ
            System.out.println("Получен запрос ключа от: " + senderEmail);

            // 1. Декодируем публичный ключ собеседника из Base64 обратно в byte[]
            byte[] peerPublicKeyBytes = Base64.decode(body.trim(), Base64.NO_WRAP);

            //TODO Надо передавать название чата по сети
            long newChatId = dbService.saveChat(senderEmail, senderEmail);

            // 2. Сохраняем его в нашу базу SQLite (в таблицу chats для этого отправителя)
            dbService.savePeerPublicKey(senderEmail, peerPublicKeyBytes);

            // 3. ОТВЕЧАЕМ СВОИМ КЛЮЧОМ
            String serverUid = sendHandshakeResponse(senderEmail, context, newChatId, localMessageId, prefs);

            deleteMessageFromMailRuServer(serverUid, context);
        } else if (subject.contains("[MailLis-Key-Response]") && !senderEmail.equals(myEmail)) {
            // СЛУЧАЙ 2: Собеседник ответил на наш запрос и прислал свой ключ
            System.out.println("Получен ответный ключ от: " + senderEmail);

            byte[] peerPublicKeyBytes = Base64.decode(body.trim(), Base64.NO_WRAP);

            // Сохраняем его в базу. Рукопожатие завершено!
            dbService.savePeerPublicKey(senderEmail, peerPublicKeyBytes);
//TODO добавить удаление сообщения с сервера
            // Теперь можно разблокировать кнопку отправки сообщений в UI
//            runOnUiThread(() -> {
//                loadRecipientPublicKeyFromDb(); // Перечитываем ключ в память активности
//                Toast.makeText(this, "Безопасное соединение установлено!", Toast.LENGTH_SHORT).show();
//            });

        }
        return true;
    }


    // Фоновый метод для удаления письма по протоколу IMAP
    public void deleteMessageFromMailRuServer(final String serverUid, Context context) {
        if (serverUid == null || serverUid.isEmpty()) {
            return;
        }

        new Thread(() -> {
            jakarta.mail.Store store = null;
            jakarta.mail.Folder inbox = null;
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);
                String username = prefs.getString("email_login", "");
                String password = prefs.getString("email_password", "");
                String imapServer = prefs.getString("imap_server", "imap.mail.ru");

                if (username.isEmpty() || password.isEmpty()) return;

                java.util.Properties props = new java.util.Properties();
                props.put("mail.store.protocol", "imaps");
                props.put("mail.imaps.host", imapServer);
                props.put("mail.imaps.port", "993");

                jakarta.mail.Session session = jakarta.mail.Session.getInstance(props, null);
                store = session.getStore("imaps");
                store.connect(imapServer, username, password);

                inbox = store.getFolder("INBOX");
                inbox.open(jakarta.mail.Folder.READ_WRITE);

                int messageCount = inbox.getMessageCount();
                // Ищем в последних 50 письмах
                int startMessage = Math.max(1, messageCount - 50);
                jakarta.mail.Message[] messages = inbox.getMessages(startMessage, messageCount);

                // Очищаем искомый заголовок от скобок и пробелов
                String cleanTargetMimeId = serverUid.replace("<", "").replace(">", "").trim();
                boolean messageFound = false;

                for (jakarta.mail.Message msg : messages) {
                    // Извлекаем заголовок Message-ID текущего письма
                    String[] mimeIds = msg.getHeader("Message-ID");
                    if (mimeIds != null && mimeIds.length > 0 && mimeIds[0] != null) {

                        // Тоже очищаем его от скобок для точного сравнения
                        String currentMimeId = mimeIds[0].replace("<", "").replace(">", "").trim();

                        if (currentMimeId.equalsIgnoreCase(cleanTargetMimeId)) {
                            // Создаем массив из одного элемента и принудительно ставим флаг удаления через папку
                            jakarta.mail.Message[] messageArray = new jakarta.mail.Message[]{ msg };
                            inbox.setFlags(messageArray, new jakarta.mail.Flags(jakarta.mail.Flags.Flag.DELETED), true);

                            android.util.Log.d("MailLis_Delete", "Письмо с Message-ID успешно найдено и удалено.");
                            messageFound = true;
                            break;
                        }
                    }
                }

                if (!messageFound) {
                    android.util.Log.e("MailLis_Delete", "Письмо с Message-ID " + cleanTargetMimeId + " не найдено в последних 50 сообщениях.");
                }

                // Закрываем с флагом true для применения удаления (Expunge)
                inbox.close(true);
                store.close();

                // Выводим тост
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, "Сообщение удалено со своего устройства и сервера", Toast.LENGTH_SHORT).show()
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
