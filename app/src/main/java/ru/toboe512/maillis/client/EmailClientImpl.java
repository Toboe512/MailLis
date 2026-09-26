package ru.toboe512.maillis.client;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_KEY_REQUEST;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_KEY_RESPONSE;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import ru.toboe512.maillis.activity.MainActivity;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.Message;

public class EmailClientImpl implements EmailClient {

    // Переменные для работы с базой данных
    private final DBService dbService;

    private final CryptoService cryptoService;


    public EmailClientImpl(DBService dbService, Context context) {
        this.dbService = dbService;
        try {
            this.cryptoService = new CryptoServiceImpl(context);
        } catch (GeneralSecurityException | IOException e) {
            android.util.Log.e("EmailClientImpl", "Ошибка инициализациии CryptoServiceImpl" + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public CryptoService getCryptoService() {
        return cryptoService;
    }

    @Override
    public List<Message> fetchIncomingHandshakeOverNetwork(Context context, SharedPreferences prefs) {
        // 1. Создаем пул потоков (в данном случае из одного потока)
        ExecutorService executor = newSingleThreadExecutor();

        // 2. Определяем задачу Callable, которая возвращает строку после паузы
        Callable<List<Message>> task = () -> {
            List<Message> messageList = new ArrayList<>();
            try {
                Store store = connectToImapServer(prefs);

                Folder inbox = store.getFolder("INBOX");
                inbox.open(jakarta.mail.Folder.READ_WRITE);

                int messageCount = inbox.getMessageCount();
                if (messageCount <= 0) {
                    return new ArrayList<>();
                };

                // Сканируем последние 25 писем, чтобы точно не пропустить ответы
                int startMessage = Math.max(1, messageCount - 25);
                jakarta.mail.Message[] messages = inbox.getMessages(startMessage, messageCount);

                for (jakarta.mail.Message msg : messages) {
                    if (msg.getSubject() == null) {
                        continue;
                    }
                    //TODO Доработать для группповых чатов
                    String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
                    // ПРОВЕРКА НА БАН: Если отправитель в черном списке — стираем письмо и игнорируем!
                    if (dbService.isUserBanned(senderEmail)) {
                        android.util.Log.w("MailLis_Security", "🛑 Заблокированный спам от " + senderEmail + " проигнорирован.");

                        // Сразу удаляем служебное/обычное письмо с сервера Mail.ru, чтобы оно не висело во входящих
                        String[] mimeIds = msg.getHeader("Message-ID");
                        if (mimeIds != null && mimeIds.length > 0) {
                            deleteMessageFromMailRuServer(mimeIds[0], context);
                        }
                        continue; // Мгновенно переходим к следующему письму, этот объект дальше не обрабатываем!
                    }

                    String subject = msg.getSubject();
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
                        }

                        // 3. ОТВЕЧАЕМ СВОИМ КЛЮЧОМ
                        String serverUid = sendHandshakeResponse(senderEmail, chatId,  prefs);
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
                android.util.Log.e("fetchIncomingHandshakeOverNetwork", "Ошибка обновления" + e.getMessage());
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
            android.util.Log.e("fetchIncomingHandshakeOverNetwork", "Ошибка обновления" + e.getMessage());
        } finally {
            // 5. Обязательно закрываем пул потоков
            executor.shutdown();
        }

        return result;
    }

    public interface OnMessagesSynchronizedListener {
        // Этот метод сработает, когда отправка завершится и база обновится
        void onSynchronized(Message updatedMessages);

        void onError(Exception e);
    }

    // Сетевая SMTP отправка на фоновом потоке
    public boolean sendEmailOverNetwork(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, OnMessagesSynchronizedListener listener) {

        if (listener == null) {
            android.util.Log.e("EmailClient.sendEmailOverNetwork listener", "Критическая ошибка listener == null");
            return false;
        }

        try (ExecutorService executor = newSingleThreadExecutor()) {
            // Handler нужен, чтобы вернуть результат в Главный (UI) поток Android
            var mainHandler = new Handler(Looper.getMainLooper());

            executor.execute(() -> {
                String myEmail = prefs.getString("email_login", "my@mail.ru");
                try {
                    // ИСПОЛЬЗУЕМ ВЫДЕЛЕННЫЙ МЕТОД: Получаем готовую SMTP сессию
                    Session session = createSmtpSession(prefs);

                    var message = new MimeMessage(session);
                    message.setFrom(new InternetAddress(myEmail));
                    message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
                    message.setSubject(subject);
                    message.setText(messageText);
                    // 1. ПРИНУДИТЕЛЬНО ПРИМЕНЯЕМ ИЗМЕНЕНИЯ, чтобы сгенерировался Message-ID
                    message.saveChanges();
                    // 2. Отправляем в сеть
                    Transport.send(message);

                    // 3. БЕЗОПАСНО ИЗВЛЕКАЕМ Message-ID
                    String generatedServerUid = message.getMessageID(); // Используем встроенный метод вместо getHeader()
                    dbService.markMessageAsSent(localMessageId, generatedServerUid);
                    dbService.updateServerUid(generatedServerUid, localMessageId);
                    // Передаем (int) chatId, если ваша БД принимает инты
                    Message freshlyLoadedMessages = dbService.getMessage(localMessageId);

                    mainHandler.post(() -> listener.onSynchronized(freshlyLoadedMessages));
                } catch (jakarta.mail.AuthenticationFailedException e) {
                    // Неверный пароль. Прерываем отправку всей пачки, так как данные авторизации сломаны.
                    android.util.Log.e("MailLis_Retry", "Критическая ошибка: неверный пароль для " + myEmail);
                    mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель

                } catch (jakarta.mail.MessagingException e) {
                    // Любая другая ошибка (чаще всего — пропала сеть).
                    // Оставляем сообщение со статусом 0 и выходим из цикла до следующего тика таймера.
                    android.util.Log.w("MailLis_Retry", "Сбой отправки сообщения , попробуем позже. Причина: " + e.getMessage()); //+ message.getId() + ", попробуем позже. Причина: " + e.getMessage());
                    mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель
                } catch (Exception e) {
                    //TODO логировааниее
                    e.printStackTrace();
                    android.util.Log.e("MailLis_Retry", "Сбой отправки сообщения Причина: " + e.getMessage());
                    mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель

                } finally {
                    // Гарантированно закрываем фоновый поток после завершения всех задач
                    executor.shutdown();
                }
            });
            return true;
        }
    }


    /**
     * Инициализирует P2P-рукопожатие: сохраняет техническое сообщение в базу данных
     * и асинхронно отправляет ваш публичный ключ Base64 собеседнику по SMTP.
     */
    public boolean sendHandshakeRequest(String targetEmail, Context context, long chatId, SharedPreferences prefs) {
        try {
            String myEmail = prefs.getString("email_login", "my@mail.ru");
            long currentTimestamp = System.currentTimeMillis();
            String myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();

            // 1. Сохраняем служебное сообщение в локальную SQLite историю
            // Метод saveMessage сам выставит нужные флаги (is_mine = 1, is_sent = 0)
            int localMessageId = dbService.saveMessage(
                    (int) chatId,
                    myEmail,
                    MAILLIS_KEY_REQUEST, // Тема письма, например "[MailLis-Key-Request]"
                    myPublicKeyBase64,   // В тело письма прячем публичный ключ устройства
                    true,
                    currentTimestamp
            );

            // 2. Запускаем асинхронную SMTP отправку через наш оптимизированный метод
            sendEmailOverNetwork(
                    chatId,
                    targetEmail,
                    MAILLIS_KEY_REQUEST,
                    myPublicKeyBase64,
                    localMessageId, // Передаем ID для последующего обновления server_uid и статуса ретрая
                    prefs,
                    new OnMessagesSynchronizedListener() {
                        @Override
                        public void onSynchronized(Message updatedMessage) {
                            // Сеть отработала успешно, сообщение улетело на Mail.ru
                            android.util.Log.d("MailLis_Handshake", "Запрос рукопожатия успешно отправлен в сеть на адрес: " + targetEmail);
                            // Помечаем сообщение как успешно отправленное в нашей базе данных
                         //   dbService.markMessageAsSent(localMessageId, updatedMessage.getServerUid());
                        }

                        @Override
                        public void onError(Exception e) {
                            // Ошибка сети или авторизации — сработает наша логика ретрая,
                            // сообщение останется со статусом is_sent = 0 в SQLite
                            android.util.Log.e("MailLis_Handshake", "Сбой отправки рукопожатия, отложено в очередь ретрая: " + e.getMessage());
                        }
                    }
            );

            return true;

        } catch (Exception e) {
            android.util.Log.e("MailLis_Handshake", "Критическая ошибка внутри метода sendHandshakeRequest", e);
            return false;
        }
    }

    /**
     * Синхронно отправляет ответный публичный ключ собеседника (Handshake Response)
     * и возвращает сгенерированный сервером Mail.ru идентификатор сообщения (serverUid).
     *
     * ВНИМАНИЕ: Вызывать строго из фонового потока!
     */
    public String sendHandshakeResponse(String targetEmail, long chatId, SharedPreferences prefs) {
        String myEmail = prefs.getString("email_login", "my@mail.ru");

        try {
            long currentTimestamp = System.currentTimeMillis();
            String myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();

            // 1. Сохраняем служебное сообщение в локальную SQLite историю
            int localMessageId = dbService.saveMessage(
                    (int) chatId,
                    myEmail,
                    MAILLIS_KEY_RESPONSE, // "[MailLis-Key-Response]"
                    myPublicKeyBase64,
                    true,
                    currentTimestamp
            );

            // 2. СИНХРОННО создаем SMTP сессию (используем наш общий метод)
            Session session = createSmtpSession(prefs);

            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(myEmail));
            message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(targetEmail));
            message.setSubject(MAILLIS_KEY_RESPONSE);
            message.setText(myPublicKeyBase64);

            // 3. Применяем изменения, чтобы сгенерировался Message-ID
            message.saveChanges();

            // 4. БЛОКИРУЮЩИЙ ВЫЗОВ: Отправляем в сеть в текущем потоке
            Transport.send(message);

            // 5. БЕЗОПАСНО ИЗВЛЕКАЕМ Message-ID сервера Mail.ru
            String generatedServerUid = message.getMessageID();

            // Обновляем ID и статус успеха в локальной базе данных SQLite
            dbService.updateServerUid(generatedServerUid, localMessageId);
            dbService.markMessageAsSent(localMessageId, generatedServerUid);

            android.util.Log.d("MailLis_Handshake", "Ответный ключ синхронно отправлен. UID: " + generatedServerUid);

            // Возвращаем долгожданный UID обратно
            return generatedServerUid;

        } catch (jakarta.mail.AuthenticationFailedException e) {
            android.util.Log.e("MailLis_Retry", "Критическая ошибка: неверный пароль для " + myEmail);
            e.printStackTrace();
        } catch (jakarta.mail.MessagingException e) {
            android.util.Log.w("MailLis_Retry", "Сбой синхронной отправки сообщения: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Если произошла ошибка, возвращаем дефолтную пустую строку во избежание крэша
        return EMPTI_STRING;
    }

    public String sendHandshakeResponseAsin(String targetEmail, long chatId, final int localMessageId, SharedPreferences prefs, CryptoService cryptoService) {
        var messages = new ArrayList<Message>();
        try {
            String myEmail = prefs.getString("email_login", "my@mail.ru");
            long currentTimestamp = System.currentTimeMillis();
            dbService.saveMessage(chatId, myEmail, MAILLIS_KEY_RESPONSE, cryptoService.getMyPublicKeyBas64(), true, currentTimestamp);
            // Отправляем свой ключ в ответ
            sendEmailOverNetwork(chatId, targetEmail, MAILLIS_KEY_RESPONSE, cryptoService.getMyPublicKeyBas64(), localMessageId, prefs,
                    new OnMessagesSynchronizedListener() {
                        @Override
                        public void onSynchronized(Message updatedMessage) {
                            android.util.Log.d("MailLis_Handshake", "Ответный ключ успешно улетел в сеть на " + targetEmail);
                            messages.add(updatedMessage);
                        }

                        @Override
                        public void onError(Exception e) {
                            // Если пропала сеть, сообщение останется в базе со статусом is_sent = 0
                            // и наш фоновый ретрай автоматически дошлет его позже
                            android.util.Log.e("MailLis_Handshake", "Ошибка при отправке ответа, отложено в ретрай: " + e.getMessage());
                        }
                    });
            System.out.println("Ответный ключ отправлен на " + targetEmail);


        } catch (Exception e) {
            android.util.Log.e("MailLis_Handshake", "Критический сбой в методе sendHandshakeResponse", e);
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
            jakarta.mail.Store store;
            jakarta.mail.Folder inbox;
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);
                String username = prefs.getString("email_login", EMPTI_STRING);
                String password = prefs.getString("email_password", EMPTI_STRING);
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
                String cleanTargetMimeId = serverUid.replace("<", EMPTI_STRING).replace(">", EMPTI_STRING).trim();
                boolean messageFound = false;

                for (jakarta.mail.Message msg : messages) {
                    // Извлекаем заголовок Message-ID текущего письма
                    String[] mimeIds = msg.getHeader("Message-ID");

                    if (mimeIds != null && mimeIds.length > 0 && mimeIds[0] != null) {

                        // Тоже очищаем его от скобок для точного сравнения
                        String currentMimeId = mimeIds[0].replace("<", EMPTI_STRING).replace(">", EMPTI_STRING).trim();

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
                android.util.Log.e("MailLis_Delete", Objects.requireNonNull(e.getMessage()));
            }
        }).start();
    }

    public void retryUnsentMessages(SharedPreferences prefs) {
        ExecutorService executor = newSingleThreadExecutor();
        List<Message> messagesList = new ArrayList<>();
        executor.execute(() -> {
            // 1. Просто запрашиваем у сервиса БД список всех неотправленных писем
            List<Message> unsentMessages = dbService.getUnsentMessages();

            if (unsentMessages.isEmpty()) {
                return; // Если очередь пуста, тихо выходим
            }

            String myEmail = prefs.getString("email_login", EMPTI_STRING);
            if (myEmail.isEmpty()) return;

            // 2. Бежим в цикле по готовым объектам сообщений
            for (Message msg : unsentMessages) {
                try {
                    // Вытаскиваем chatId для этого сообщения (нужно будет получить recipientEmail)
                    // Если вы добавили поле chatId в класс Message: int chatId = msg.getChatId();
                    // Если нет — получаем chatId через вспомогательный метод по тексту/временной метке:
                    int chatId = dbService.getChatIdByMessageId(msg.getId());
                    String recipientEmail = dbService.getRecipientEmailByChatId(chatId);


                    if (recipientEmail == null || recipientEmail.isEmpty()) {
                        continue;
                    }

                    // Переотправляем письмо по SMTP
                    sendEmailOverNetwork(chatId, recipientEmail, msg.getSubject(), msg.getText(), msg.getId(), prefs, new OnMessagesSynchronizedListener() {
                        @Override
                        public void onSynchronized(Message updatedMessage) {
                            messagesList.add(updatedMessage);
                        }

                        @Override
                        public void onError(Exception e) {

                        }
                    });


                    if (messagesList.get(0) != null) {
                        // 3. Сеть прошла успешно! Помечаем сообщение в базе как отправленное
                        dbService.markMessageAsSent(msg.getId(), messagesList.get(0).getServerUid());
                        android.util.Log.d("MailLis_Retry", "Сообщение с ID " + msg.getId() + " успешно отправлено после восстановления сети.");
                    }


                } catch (Exception e) {
                    // Если интернета всё еще нет — SMTP выкинет ошибку.
                    // Прерываем отправку пачки до следующего тика таймера (через 20 секунд)
                    android.util.Log.w("MailLis_Retry", "Ретрай не удался, сеть по-прежнему недоступна: " + e.getMessage());
                    break;
                }
            }
        });
    }

    private Store connectToImapServer(SharedPreferences prefs) throws MessagingException {
        final String username = prefs.getString("email_login", EMPTI_STRING);
        final String password = prefs.getString("email_password", EMPTI_STRING);
        String imapServer = prefs.getString("imap_server", "imap.mail.ru");
        String imapPort = prefs.getString("imap_port", "993");

        if (username.isEmpty() || password.isEmpty()) {
            throw new AuthenticationFailedException("Логин или пароль для Mail.ru не заполнены.");
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", imapServer);
        props.put("mail.imaps.port", imapPort);
        props.put("mail.imaps.timeout", "15000");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.partialfetch", "false");

        // Безопасность и обход блокировок Android 10+
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.imaps.socketFactory.fallback", "false");
        props.put("mail.imaps.socketFactory.port", imapPort);
        props.put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3");

        if ("143".equals(imapPort)) {
            props.put("mail.imaps.starttls.enable", "true");
            props.put("mail.imaps.starttls.required", "true");
        }

        Session session = Session.getInstance(props, null);
        Store store = session.getStore("imaps");
        store.connect(imapServer, username, password);

        return store;
    }

    private Session createSmtpSession(SharedPreferences prefs) throws MessagingException {
        final String username = prefs.getString("email_login", EMPTI_STRING);
        final String password = prefs.getString("email_password", EMPTI_STRING);
        String smtpServer = prefs.getString("smtp_server", "smtp.mail.ru");
        String smtpPort = prefs.getString("smtp_port", "465");

        if (username.isEmpty() || password.isEmpty()) {
            throw new AuthenticationFailedException("Логин или пароль для SMTP Mail.ru не заполнены.");
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpServer);
        props.put("mail.smtp.socketFactory.port", smtpPort);
        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.port", smtpPort);

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }

}
