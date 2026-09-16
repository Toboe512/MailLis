package ru.toboe512.maillis.client;

import static ru.toboe512.maillis.consts.Consts.*;

import android.content.Context;
import android.content.SharedPreferences;
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

    private CryptoService cryptoService;


    public EmailClientImpl(ChatDbHelper dbHelper, DBService dbService, CryptoService cryptoService) {
        this.dbHelper = dbHelper;
        this.dbService = dbService;
        this.cryptoService = cryptoService;
    }

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
                    if (msg.getSubject() != null && (msg.getSubject().equals(MAILLIS_CHAT_MESSAGE)
                            || msg.getSubject().contains(MAILLIS_KEY_REQUEST)
                            || msg.getSubject().contains(MAILLIS_KEY_RESPONSE))) {
//TODO
                        String sender = ((InternetAddress) msg.getFrom()[0]).getAddress();
                        String text = msg.getContent().toString();


                        long currentTimestamp = msg.getSentDate() != null ? msg.getSentDate().getTime() : System.currentTimeMillis();
                        String[] mimeIds = msg.getHeader("Message-ID");
                        String messageId = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;

                        dbService.saveIncomingMessage(chatId, sender, MAILLIS_CHAT_MESSAGE, text, currentTimestamp, messageId);
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
                inbox.open(jakarta.mail.Folder.READ_WRITE);

                int messageCount = inbox.getMessageCount();
                if (messageCount <= 0) return new ArrayList<>();

                // Сканируем последние 25 писем, чтобы точно не пропустить ответы
                int startMessage = Math.max(1, messageCount - 25);
                jakarta.mail.Message[] messages = inbox.getMessages(startMessage, messageCount);

                for (jakarta.mail.Message msg : messages) {
                    if (msg.getSubject() == null) {
                        continue;
                    }
                    String subject = msg.getSubject();
                    String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();

                    String myEmail = prefs.getString("email_login", "my@mail.ru");

                    // СЛУЧАЙ В: Прилетело обычное текстовое сообщение чата
                    if (subject.contains(MAILLIS_CHAT_MESSAGE)) {
                        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
                        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
                        if (chatId > 0) {
                            // Если чат существует у обоих — сохраняем сообщение в базу
                            String messageText = msg.getContent().toString();

                            byte[] sharedSecret = dbService.getSharedSecret(chatId);
                            // Расшифровываем СВОИМ приватным ключом (метод decode больше не требует чужих ключей!)
                            if (!senderEmail.equals(myEmail)) {
                                messageText = sharedSecret != null ? cryptoService.decode(messageText) : messageText;
                                long currentTimestamp = msg.getSentDate() != null ? msg.getSentDate().getTime() : System.currentTimeMillis();
                                String[] mimeIds = msg.getHeader("Message-ID");
                                String messageId = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;
                                dbService.saveIncomingMessage(chatId, senderEmail, MAILLIS_CHAT_MESSAGE, messageText, currentTimestamp, messageId);
                            }

                            android.util.Log.d("MailLis_P2P", "Новое сообщение успешно добавлено в чат " + chatId);
                        } else {
                            // СТРОГОЕ ПРАВИЛО: Если один из пользователей удалил чат,
                            // обычные сообщения от него просто тихо стираются с сервера и игнорируются!
                            android.util.Log.w("MailLis_P2P", "Чат удален или не создан. Сообщение от " + senderEmail + " проигнорировано.");
                            String[] mimeIds = msg.getHeader("Message-ID");
                            String messageId = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;

                            if (messageId != null) {
                                deleteMessageFromMailRuServer(messageId, context);
                            }
                        }
                        continue;
                    }

                    //Далее блок ловли хендшейка свои майлы нам не интересны

                    if (senderEmail.contains(myEmail)) {
                        continue;
                    }

                    // СЛУЧАЙ А: Прилетел запрос на создание чата и обмен ключами
                    if (subject.contains(MAILLIS_KEY_REQUEST)) {
                        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
                        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
                        // Если чата еще нет (или он был удален ранее) — создаем его заново!
                        if (chatId < 0) {
                            chatId = dbService.saveChat(senderEmail, senderEmail);
                            android.util.Log.d("MailLis_P2P", "Получен запрос. Чат успешно создан у получателя. ID: " + chatId);
                        }

                        // Вызываем ваш метод обработки хендшейка (сохраняем ключ собеседника и шлем ответ)
                        // СЛУЧАЙ 1: Нам прислали ключ и просят наш в ответ
                        System.out.println("Получен запрос ключа от: " + senderEmail);

                        // 1. Декодируем публичный ключ собеседника из Base64 обратно в byte[]
                        String trim = msg.getContent().toString().trim();
                        byte[] peerPublicKeyBytes = Base64.decode(trim, Base64.NO_WRAP);

                        //TODO Надо передавать название чата по сети
                        // 2. Сохраняем его в нашу базу SQLite (в таблицу chats для этого отправителя)
                        dbService.saveSharedSecretKey(senderEmail, peerPublicKeyBytes);

                        // Стираем служебное письмо с сервера Mail.ru, чтобы оно не висело во входящих
                        String[] mimeIds = msg.getHeader("Message-ID");
                        String messageId = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;

                        if (messageId != null) {
                            // Создаем массив из одного элемента и принудительно ставим флаг удаления через папку
                            jakarta.mail.Message[] messageArray = new jakarta.mail.Message[]{msg};
                            inbox.setFlags(messageArray, new jakarta.mail.Flags(jakarta.mail.Flags.Flag.DELETED), true);
                            // 2. ДОБАВИТЬ СТРОКУ: Принудительно заставляем IMAP-сервер физически стереть помеченные сообщения прямо сейчас
                            inbox.expunge();
                            android.util.Log.d("MailLis_Delete", "Письмо с Message-ID успешно найдено и удалено.");
                        };
                        // 3. ОТВЕЧАЕМ СВОИМ КЛЮЧОМ
                        String serverUid = sendHandshakeResponse(senderEmail, chatId, 0, prefs, cryptoService);
                        deleteMessageFromMailRuServer(serverUid, context);
                        continue;
                    }

                    // СЛУЧАЙ Б: Прилетел ответный ключ (собеседник подтвердил создание нашего чата)
                    if (subject.contains(MAILLIS_KEY_RESPONSE)) {
                        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
                        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
                        if (chatId > 0) { // Обрабатываем, только если чат у нас существует
                            // СЛУЧАЙ 2: Собеседник ответил на наш запрос и прислал свой ключ
                            System.out.println("Получен ответный ключ от: " + senderEmail);

                            byte[] peerPublicKeyBytes = Base64.decode(msg.getContent().toString().trim(), Base64.NO_WRAP);

                            // Сохраняем его в базу. Рукопожатие завершено!
                            dbService.saveSharedSecretKey(senderEmail, peerPublicKeyBytes);
                        }
                        String[] mimeIds = msg.getHeader("Message-ID");
                        String messageId = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;

                        if (messageId != null) {
                            // Создаем массив из одного элемента и принудительно ставим флаг удаления через папку
                            jakarta.mail.Message[] messageArray = new jakarta.mail.Message[]{msg};
                            inbox.setFlags(messageArray, new jakarta.mail.Flags(jakarta.mail.Flags.Flag.DELETED), true);
                            // 2. ДОБАВИТЬ СТРОКУ: Принудительно заставляем IMAP-сервер физически стереть помеченные сообщения прямо сейчас
                            inbox.expunge();
                            android.util.Log.d("MailLis_Delete", "Письмо с Message-ID успешно найдено и удалено.");
                        }
                    }


                }
                // Закрываем с флагом true для применения удаления (Expunge)
                inbox.close(true);
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
        } catch (InterruptedException |
                 ExecutionException e) {
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

                    dbService.updateServerUid(generatedServerUid, localMessageId);


                    // 4. Проверяем, что сервис базы данных существует перед вызовом
                    if (dbService != null) {
                        String myEmail = prefs.getString("email_login", "my@mail.ru");
                        // Передаем (int) chatId, если ваша БД принимает инты
                        List<Message> freshlyLoadedMessages = dbService.getMessages(chatId, myEmail);

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


    public byte[] sendHandshakeRequest(String targetEmail, Context context, long chatId, SharedPreferences prefs, String myPublicKeyBase64) {
        byte[] myPublicKeyBytes = null;
        try {
            String myEmail = prefs.getString("email_login", "my@mail.ru");
            long currentTimestamp = System.currentTimeMillis();
            dbService.saveMessage(chatId, myEmail, MAILLIS_KEY_REQUEST, myPublicKeyBase64, currentTimestamp);
            sendEmailOverNetwork(chatId, targetEmail, MAILLIS_KEY_REQUEST, myPublicKeyBase64,
                    0, prefs, new OnMessagesSynchronizedListener() {
                        @Override
                        public void onSynchronized(List<Message> updatedMessages) {
                            //messageList.addAll(updatedMessages);
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

    public String sendHandshakeResponse(String targetEmail, long chatId, final int localMessageId, SharedPreferences prefs, CryptoService cryptoService) {


        List<Message> messages = new ArrayList<>();

        try {
            String myEmail = prefs.getString("email_login", "my@mail.ru");
            long currentTimestamp = System.currentTimeMillis();
            dbService.saveMessage(chatId, myEmail, MAILLIS_KEY_REQUEST, cryptoService.getMyPublicKeyBas64(), currentTimestamp);
            // Отправляем свой ключ в ответ
            sendEmailOverNetwork(chatId, targetEmail, MAILLIS_KEY_RESPONSE, cryptoService.getMyPublicKeyBas64(), localMessageId, prefs,
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
                            jakarta.mail.Message[] messageArray = new jakarta.mail.Message[]{msg};
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
