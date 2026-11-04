package ru.toboe512.maillis.client;

import static android.util.Base64.NO_WRAP;
import static java.lang.Boolean.FALSE;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static jakarta.mail.Flags.Flag.DELETED;
import static jakarta.mail.Folder.READ_WRITE;
import static jakarta.mail.Message.RecipientType.TO;
import static ru.toboe512.maillis.consts.Consts.CHAT_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.CHAT_MESSAGE;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.GROUP_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_INVITE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_LEAVE;
import static ru.toboe512.maillis.consts.Consts.GROUP_MESSAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_PRE_INVITE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_PRE_INVITE_RESPONSE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_SYNC;
import static ru.toboe512.maillis.consts.Consts.IMAGE_MSG;
import static ru.toboe512.maillis.consts.Consts.INBOX;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_PASSWORD;
import static ru.toboe512.maillis.consts.Consts.KEY_IMAP_PORT;
import static ru.toboe512.maillis.consts.Consts.KEY_IMAP_SERVER;
import static ru.toboe512.maillis.consts.Consts.KEY_REQUEST;
import static ru.toboe512.maillis.consts.Consts.KEY_RESPONSE;
import static ru.toboe512.maillis.consts.Consts.KEY_SMTP_PORT;
import static ru.toboe512.maillis.consts.Consts.KEY_SMTP_SERVER;
import static ru.toboe512.maillis.consts.Consts.READ_RECEIPT;
import static ru.toboe512.maillis.consts.Consts.SPACE_STRING;
import static ru.toboe512.maillis.consts.Consts.TEXT_MSG;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.MessageIDTerm;
import jakarta.mail.search.SearchTerm;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.MessageDto;

public class EmailClientImpl implements EmailClient {

    // Переменные для работы с базой данных
    private final DBService dbService;

    private final CryptoService cryptoService;

    // ГЛОБАЛЬНЫЙ ЕДИНЫЙ ПУЛ ПОТОКОВ ДЛЯ ВСЕЙ СЕТИ ПРИЛОЖЕНИЯ
    private final ExecutorService networkExecutor;

    private static class Holder {
        private static EmailClient INSTANCE;
    }

    public static EmailClient getInstance(DBService dbService, Context context) {
        if (Holder.INSTANCE == null) {
            Holder.INSTANCE = new EmailClientImpl(dbService, context);
        }
        return Holder.INSTANCE;
    }


    private EmailClientImpl(DBService dbService, Context context) {
        this.dbService = dbService;
        // Создаем ОДИН поток на всё приложение. Задачи будут выполняться строго друг за другом!
        this.networkExecutor = Executors.newSingleThreadExecutor();
        try {
            this.cryptoService = new CryptoServiceImpl(context);
        } catch (GeneralSecurityException | IOException e) {
            Log.e("EmailClientImpl", "Ошибка инициализациии CryptoServiceImpl" + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public CryptoService getCryptoService() {
        return cryptoService;
    }

    @Override
    public boolean fetchIncomingOverNetwork(Context context, SharedPreferences prefs) {
        // 1. Создаем пул потоков (в данном случае из одного потока)
        ExecutorService executor = newSingleThreadExecutor();

        // 2. Определяем задачу Callable, которая возвращает строку после паузы
        Callable<Boolean> task = () -> {
            boolean result = false;
            try {
                Store store = connectToImapServer(prefs);
                Folder inbox = store.getFolder(INBOX);
                inbox.open(READ_WRITE);

                int messageCount = inbox.getMessageCount();
                if (messageCount <= 0) {
                    return result;
                }

                // Сканируем последние 25 писем, чтобы точно не пропустить ответы
                int startMessage = Math.max(1, messageCount - 25);
                Message[] messages = inbox.getMessages(startMessage, messageCount);

                for (Message msg : messages) {
                    String subject = msg.getSubject();
                    if (subject == null) {
                        continue;
                    }

                    String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
                    // ПРОВЕРКА НА БАН: Если отправитель в черном списке — стираем письмо и игнорируем!
                    if (dbService.isUserBanned(senderEmail)) {
                        Log.w("MailLis_Security", "🛑 Заблокированный спам от " + senderEmail + " проигнорирован.");
                        deleteMessage(msg, inbox);
                        continue; // Мгновенно переходим к следующему письму, этот объект дальше не обрабатываем!
                    }

                    // СЛУЧАЙ В: Прилетело обычное текстовое сообщение чата
                    if (subject.contains(CHAT_MESSAGE)) {
                        result = chatMessageCase(msg, prefs);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    if (subject.contains(CHAT_IMAGE_MAIL)) {
                        result = chatImageCase(msg, context.getCacheDir(), prefs);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    if (subject.contains(GROUP_MESSAGE_MAIL)) {
                        result = groupMessageCase(msg);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    if (subject.contains(GROUP_IMAGE_MAIL)) {
                        result = groupImageCase(msg, context.getCacheDir());
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    if (subject.contains(READ_RECEIPT)) {
                        result = readReceipt(msg);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    if (subject.contains(GROUP_LEAVE)) {
                        result = groupLeave(msg);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
                    //Далее блок ловли хендшейка свои майлы нам не интересны
                    if (senderEmail.contains(myEmail)) {
                        continue;
                    }
                    //  В СУЩЕСТВУЮЩУЮ ГРУППУ ДОБАВИЛСЯ КТО-ТО ЕЩЕ (Синхронизация состава)
                    if (subject.contains(GROUP_SYNC)) {
                        result = groupSync(msg);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    // СЦЕНАРИЙ 1: Нам прилетел Pre-Invite (Мы получатель, у нас просят ключ для вступления)
                    if (subject.contains(GROUP_PRE_INVITE_MAIL)) {
                        result = groupPreInviteCase(msg, prefs);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    // СЦЕНАРИЙ 2: Создателю группы прилетел ответный Tink-ключ от друга
                    if (subject.contains(GROUP_PRE_INVITE_RESPONSE_MAIL)) {
                        result = groupPreInviteResponseCase(msg, prefs);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    // СЦЕНАРИЙ 3: Получателю прилетел долгожданный ЗАШИФРОВАННЫЙ AES-ключ группы
                    if (subject.contains(GROUP_INVITE_MAIL)) {
                        result = groupInviteCase(msg, inbox);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    // СЛУЧАЙ А: Прилетел запрос на создание чата и обмен ключами
                    if (subject.contains(KEY_REQUEST)) {
                        result = keyRequestCase(msg, prefs);
                        deleteMessage(msg, inbox);
                        continue;
                    }
                    // СЛУЧАЙ Б: Прилетел ответный ключ (собеседник подтвердил создание нашего чата)
                    if (subject.contains(KEY_RESPONSE)) {
                        result = keyResponseCase(msg);
                        deleteMessage(msg, inbox);
                    }
                }
                // Закрываем с флагом true для применения удаления (Expunge)
                inbox.close(true);
                store.close();
            } catch (Exception e) {
                Log.e("fetchIncomingHandshakeOverNetwork", "Ошибка обновления" + e.getMessage());
                e.printStackTrace();
            }
            return result;
        };

        // 3. Отправляем задачу в пул и получаем объект Future
        Future<Boolean> future = executor.submit(task);
        Boolean result = FALSE;
        try {
            // 4. Метод get() блокирует текущий поток и ждет завершения задачи
            result = future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e("fetchIncomingHandshakeOverNetwork", "Ошибка обновления" + e.getMessage());
            e.printStackTrace();
        } finally {
            // 5. Обязательно закрываем пул потоков
            executor.shutdown();
        }
        return result;
    }

    private boolean groupSync(Message msg) {
        try {
            String[] parts = msg.getSubject().split(" ");
            long remoteChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;
            String newMemberEmail = msg.getContent().toString().trim().toLowerCase();

            if (remoteChatId > 0 && !newMemberEmail.isEmpty()) {
                // Просто дописываем нового участника в локальную таблицу group_members
                dbService.addGroupMember(remoteChatId, newMemberEmail);

                // Пишем тихое системное уведомление в чат
                dbService.saveIncomingGroupMessage(remoteChatId, "Система", "[MailLis-System]",
                        "➕ К группе присоединился участник: " + newMemberEmail,
                        System.currentTimeMillis(), extractMsgId(msg), "TEXT", null);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean groupLeave(Message msg) {
        try {
            String[] parts = msg.getSubject().split(" ");
            long remoteGroupId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            // Читаем из тела письма, какой именно Email покинул чат
            String leaverEmail = msg.getContent().toString().trim().toLowerCase();

            if (remoteGroupId > 0 && !leaverEmail.isEmpty()) {
                // Принудительно вырезаем его из SQLite участников и кэша публичных ключей Tink
                dbService.removeGroupMemberByEmail(remoteGroupId, leaverEmail);

                // Локально пишем системное сообщение в чат для уведомления пользователя
                dbService.saveIncomingGroupMessage(remoteGroupId, "Система",
                        GROUP_LEAVE, "🏃 Пользователь " + leaverEmail + " покинул чат.",
                        System.currentTimeMillis(), extractMsgId(msg), "TEXT", null);

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    private boolean readReceipt(Message msg) throws MessagingException, IOException {
        // 💥 ПОЙМАЛИ ОТЧЕТ О ПРОЧТЕНИИ ЧАТА!
        // В теле этого письма друг прислал нам Message-ID нашего же отправленного ранее сообщения
        String readMessageServerUid = msg.getContent().toString().trim();
        // Обновляем статус сообщения в SQLite на "2" (Две галочки)
        dbService.markMessageAsRead(readMessageServerUid);
        Log.d("MailLis_Status", "Собеседник прочитал наше сообщение! Статус обновлен на ✓✓");
        return true;
    }

    private boolean groupMessageCase(Message msg) throws Exception {
        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        // Парсим ID родительского чата из темы письма
        String[] parts = msg.getSubject().split(SPACE_STRING);
        long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

        if (groupChatId <= 0 || !dbService.isGroupChat(groupChatId)) {
            return false;
        }

        // АВТО-СИНХРОНИЗАЦИЯ: Достаем служебный заголовок состава участников
        String[] membersHeader = msg.getHeader("X-MailLis-Group-Members");
        if (membersHeader != null && membersHeader.length > 0) {
            String[] incomingEmails = membersHeader[0].split(",");
            for (String email : incomingEmails) {
                // Метод addGroupMember внутри себя сам проверит дубликаты и привяжет к нужному localId!
                dbService.addGroupMember(groupChatId, email.trim().toLowerCase());
            }
        }

        byte[] groupKey = dbService.getGroupSecretKey(groupChatId);
        String base64Text = msg.getContent().toString().trim();
        byte[] encryptedBytes = Base64.decode(base64Text, NO_WRAP);

        // Дешифруем общим симметричным ключом группы
        String decryptedText = CryptoServiceImpl.decryptGroupText(encryptedBytes, groupKey);
        long currentTimestamp = msg.getSentDate() != null
                ? msg.getSentDate().getTime()
                : System.currentTimeMillis();
        String messageId = extractMsgId(msg);

        // Сохраняем в messages, привязывая к ID группы
        dbService.saveIncomingGroupMessage(groupChatId, senderEmail, GROUP_MESSAGE_MAIL,
                decryptedText, currentTimestamp, messageId, TEXT_MSG, EMPTI_STRING);

        return true;
    }

    private boolean groupImageCase(Message msg, File cacheDir) throws Exception {
        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        // Парсим ID родительского чата из темы письма
        String[] parts = msg.getSubject().split(SPACE_STRING);
        long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

        if (groupChatId <= 0 || !dbService.isGroupChat(groupChatId)) {
            return false;
        }

        byte[] groupKey = dbService.getGroupSecretKey(groupChatId);

        // 1. Извлекаем Multipart контент из письма
        MimeMultipart multipart = (MimeMultipart) msg.getContent();
        MimeBodyPart part = (MimeBodyPart) multipart.getBodyPart(0);

        String rawBase64 = part.getContent().toString();
        // ОПТИМИЗАЦИЯ СЕТЕВОГО ТЕКСТА: Жестко вырезаем все скрытые переносы строк (\r, \n)
        // и пробелы, которые Mail.ru вставил во вложение для транспортировки!
        String cleanBase64EncryptedImage = rawBase64
                .replaceAll("\\r", EMPTI_STRING)
                .replaceAll("\\n", EMPTI_STRING)
                .trim();
        byte[] encryptedBytes = Base64.decode(cleanBase64EncryptedImage, NO_WRAP);

        // Дешифруем общим симметричным ключом группы
        byte[] decryptedBytes = CryptoServiceImpl.decryptGroupByte(encryptedBytes, groupKey);

        // 4. СОХРАНЯЕМ ФАЙЛ В ИЗОЛИРОВАННЫЙ КЭШ ТЕЛЕФОНА
        String fileName = "p2p_img_" + System.currentTimeMillis() + ".jpg";
        File file = new File(cacheDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(decryptedBytes);
        }

        // 5. ЗАПИСЫВАЕМ В SQLite
        long currentTimestamp = msg.getSentDate() != null
                ? msg.getSentDate().getTime()
                : System.currentTimeMillis();
        String messageId = extractMsgId(msg);

        // Сохраняем в messages, привязывая к ID группы
        dbService.saveIncomingGroupMessage(groupChatId, senderEmail, GROUP_IMAGE_MAIL,
                EMPTI_STRING, currentTimestamp, messageId, IMAGE_MSG, file.getAbsolutePath());

        return true;
    }

    private boolean groupPreInviteCase(Message msg, SharedPreferences prefs) throws MessagingException {

        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        try {
            // Парсим ID чата, назначенный создателем группы
            String[] parts = msg.getSubject().split(SPACE_STRING);
            long remoteChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            if (remoteChatId <= 0) {
                return false;
            }

            String body = msg.getContent().toString().trim();
            String[] bodyLines = body.split("\\r\\n");

            String incomingGroupName = bodyLines[0].trim();
            String creatorPublicKeyBase64 = bodyLines[1].trim();

            byte[] creatorPublicKeyBytes = Base64.decode(creatorPublicKeyBase64, NO_WRAP);

            // АВТО-СИНХРОНИЗАЦИЯ: Достаем служебный заголовок состава участников
            String[] membersHeader = msg.getHeader("X-MailLis-Group-Members");
            if (membersHeader != null && membersHeader.length > 0) {
                String[] incomingEmails = membersHeader[0].split(",");
                for (String email : incomingEmails) {
                    // Метод addGroupMember внутри себя сам проверит дубликаты и привяжет к нужному localId!
                    dbService.addGroupMember(remoteChatId, email.trim().toLowerCase());
                }
            }

            // 1. Сохраняем публичный Tink-ключ создателя группы в таблицу контактов,
            // чтобы мы могли шифровать сообщения лично для него
            dbService.savePublicKey(senderEmail, creatorPublicKeyBytes);

            // 2. Создаем у себя заготовку группового чата (пока без секретного AES-ключа группы)
            dbService.saveReceivedGroupChat(remoteChatId, incomingGroupName, null, senderEmail);

            Log.i("MailLis_P2P_Group", "Поймали Pre-Invite в группу \"" + incomingGroupName + "\". Высылаем свой Tink-ключ в ответ...");

            // 3. АВТО-ОТВЕТ: Мгновенно отправляем создателю наш публичный Tink-ключ, чтобы он запечатал инвайт
            sendGroupPreInviteResponse(senderEmail, remoteChatId, prefs);

        } catch (Exception e) {
            Log.e("MailLis_P2P_Group", "Ошибка обработки Pre-Invite", e);
            return false;
        }
        return true;
    }

    private boolean groupPreInviteResponseCase(Message msg, SharedPreferences prefs) throws MessagingException {
        try {
            String[] parts = msg.getSubject().split(" ");
            long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            if (groupChatId <= 0) {
                return false;
            }
            String peerPublicKeyBase64 = msg.getContent().toString().trim();
            byte[] peerPublicKeyBytes = Base64.decode(peerPublicKeyBase64, NO_WRAP);

            String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
            // 1. Сохраняем полученный публичный Tink-ключ друга в контакты
            dbService.savePublicKey(senderEmail, peerPublicKeyBytes);
            dbService.addGroupMember(groupChatId, senderEmail); // Фиксируем, что он в группе

            Log.i("MailLis_P2P_Group", "Участник " + senderEmail + " прислал свой Tink-ключ. Досылаем ему секретный AES-ключ группы...");

            // 2. КЛЮЧ ПОЛУЧЕН! Теперь мы можем взять секретный AES-ключ группы из SQLite
            byte[] groupSecretKey = dbService.getGroupSecretKey(groupChatId);
            String groupName = dbService.getRecipientEmailByChatId(groupChatId); // Имя группы лежит тут

            // 3. Перевызываем метод отправки инвайта! Теперь у нас ЕСТЬ его ключ,
            // и метод сработает по Варианту А (отправит зашифрованный AES-пакет)
            sendGroupInviteOverNetwork(senderEmail, groupChatId, groupName, groupSecretKey, prefs);

        } catch (Exception e) {
            Log.e("MailLis_P2P_Group", "Ошибка обработки Pre-Invite-Response", e);
        }
        return true;
    }

    private boolean groupInviteCase(Message msg, Folder inbox) throws MessagingException, GeneralSecurityException, IOException {

        // Извлекаем ID чата, который назначил создатель группы
        String[] subjectParts = msg.getSubject().split(" ");
        long remoteChatId = subjectParts.length > 1 ? Long.parseLong(subjectParts[1].trim()) : -1;

        if (remoteChatId <= 0) {
            return false;
        }
        // Читаем тело письма: 1 строка - имя группы, 2 строка - Base64 зашифрованного ключа
        String body = msg.getContent().toString();
        String[] bodyLines = body.split("\\r\\n");

        String incomingGroupName = bodyLines[0].trim();
        String base64EncryptedKey = bodyLines[1].trim();

        byte[] encryptedKeyBytes = Base64.decode(base64EncryptedKey, NO_WRAP);

        // Расшифровываем СВОИМ локальным приватным Tink-ключом Keystore полученный AES-секрет группы!
        byte[] decryptedGroupKey = cryptoService.decryptBytes(encryptedKeyBytes);

        String creatorEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        // Перезаписываем заготовку чата, добавляя туда рабочий расшифрованный BLOB секретного ключа!
        dbService.saveReceivedGroupChat(remoteChatId, incomingGroupName, decryptedGroupKey, creatorEmail);

        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        dbService.addGroupMember(remoteChatId, senderEmail); // Фиксируем, что он в группе

        Log.i("MailLis_P2P_Group", "🎉 Успешно получили и расшифровали AES-ключ группы \"" + incomingGroupName + "\". Теперь мы полноценный участник!");

        deleteMessage(msg, inbox);

        return true;
    }

    private boolean chatMessageCase(Message msg, SharedPreferences prefs) throws MessagingException, IOException, GeneralSecurityException {

        boolean result;
        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

        if (senderEmail.equals(myEmail)) {
            return false;
        }

        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        if (chatId <= 0) {
            // СТРОГОЕ ПРАВИЛО: Если один из пользователей удалил чат,
            // обычные сообщения от него просто тихо стираются с сервера и игнорируются!
            Log.w("MailLis_P2P", "Чат удален или не создан. Сообщение от " + senderEmail + " проигнорировано.");
            return false;
        }
        // Если чат существует у обоих — сохраняем сообщение в базу
        String messageText = msg.getContent().toString();
        byte[] sharedSecret = dbService.getPublicKey(chatId);

        messageText = sharedSecret != null ? cryptoService.decrypted(messageText) : messageText;
        long currentTimestamp = msg.getSentDate() != null ? msg.getSentDate().getTime() : System.currentTimeMillis();

        String messageId = extractMsgId(msg);
        result = dbService.saveIncomingMessage(chatId, senderEmail, CHAT_MESSAGE,
                messageText, currentTimestamp, messageId, TEXT_MSG, EMPTI_STRING);

        // СРАЗУ ШЛЕМ СИГНАЛ ПРОЧТЕНИЯ: Отправляем скрытое SMTP письмо обратно другу
        if (messageId != null && !messageId.isEmpty()) {
          //  int generatedId = dbService.saveMessage(chatId, myEmail, CHAT_MESSAGE, EMPTI_STRING, currentTimestamp);
            // Отправляем пустой текст, главное — тема [MailLis-Read-Receipt] и Message-ID в теле или теме
            sendEmailOverNetwork(chatId, senderEmail, READ_RECEIPT, messageId, 0, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {
                @Override
                public void onSynchronized(MessageDto updatedMessages) {
                    Log.d("MailLis_Status", "Отчет о прочтении (✓✓) отправлен для письма: " + messageId);
                }

                @Override
                public void onError(Exception e) {
                    // Если не отправлено ничего страшного
                }
            });
        }
        Log.d("MailLis_P2P", "Новое сообщение успешно добавлено в чат " + chatId);
        return result;
    }

    private boolean chatImageCase(Message msg, File cacheDir, SharedPreferences prefs) throws MessagingException, IOException, GeneralSecurityException {

        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

        if (senderEmail.equals(myEmail)) {
            return false;
        }

        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        if (chatId <= 0) {
            return false;
        }

        // 1. Извлекаем Multipart контент из письма
        MimeMultipart multipart = (MimeMultipart) msg.getContent();
        MimeBodyPart part = (MimeBodyPart) multipart.getBodyPart(0);

        String rawBase64 = part.getContent().toString();
        // ОПТИМИЗАЦИЯ СЕТЕВОГО ТЕКСТА: Жестко вырезаем все скрытые переносы строк (\r, \n)
        // и пробелы, которые Mail.ru вставил во вложение для транспортировки!
        String cleanBase64EncryptedImage = rawBase64
                .replaceAll("\\r", EMPTI_STRING)
                .replaceAll("\\n", EMPTI_STRING)
                .trim();
        byte[] encryptedBytes = Base64.decode(cleanBase64EncryptedImage, NO_WRAP);

        // 3. РАСШИФРОВЫВАЕМ ПРИВАТНЫМ КЛЮЧОМ TINK
        byte[] decryptedBytes = cryptoService.decryptBytes(encryptedBytes); //Base64.decode(cleanBase64EncryptedImage, Base64.NO_WRAP);//Base64.decode(cleanBase64EncryptedImage, Base64.NO_WRAP);//

        // 4. СОХРАНЯЕМ ФАЙЛ В ИЗОЛИРОВАННЫЙ КЭШ ТЕЛЕФОНА
        String fileName = "p2p_img_" + System.currentTimeMillis() + ".jpg";
        File file = new File(cacheDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(decryptedBytes);
        }

        // 5. ЗАПИСЫВАЕМ В SQLite
        long currentTimestamp = msg.getSentDate() != null
                ? msg.getSentDate().getTime()
                : System.currentTimeMillis();
        String messageId = extractMsgId(msg);

        // Сохраняем запись в базу данных, указав тип 'IMAGE' и путь к файлу на диске
        return dbService.saveIncomingMessage(chatId, senderEmail, CHAT_IMAGE_MAIL, EMPTI_STRING,
                currentTimestamp, messageId, IMAGE_MSG, file.getAbsolutePath());
    }

    private boolean keyRequestCase(Message msg, SharedPreferences prefs) throws MessagingException, IOException {
        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        // Если чата еще нет (или он был удален ранее) — создаем его заново!
        if (chatId < 0) {
            chatId = dbService.saveChat(senderEmail, senderEmail);
            Log.d("MailLis_P2P", "Получен запрос. Чат успешно создан у получателя. ID: " + chatId);
        }

        // СЛУЧАЙ 1: Нам прислали ключ и просят наш в ответ
        // Декодируем публичный ключ собеседника из Base64 обратно в byte[]
        String trim = msg.getContent().toString().trim();
        byte[] peerPublicKeyBytes = Base64.decode(trim, NO_WRAP);

        //TODO Надо передавать название чата по сети

        // Сохраняем его в нашу базу SQLite (в таблицу chats для этого отправителя)
        dbService.savePublicKey(senderEmail, peerPublicKeyBytes);

        // 3. ОТВЕЧАЕМ СВОИМ КЛЮЧОМ
        String serverUid = sendHandshakeResponse(senderEmail, chatId, prefs);
        deleteMessageFromServer(serverUid, prefs);

        return true;
    }

    private boolean keyResponseCase(Message msg) throws MessagingException, IOException {
        boolean result = false;
        String senderEmail = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        if (chatId > 0) { // Обрабатываем, только если чат у нас существует
            // СЛУЧАЙ 2: Собеседник ответил на наш запрос и прислал свой ключ
            byte[] peerPublicKeyBytes = Base64.decode(msg.getContent().toString().trim(), NO_WRAP);
            // Сохраняем его в базу. Рукопожатие завершено!
            dbService.savePublicKey(senderEmail, peerPublicKeyBytes);
            result = true;
        }
        return result;
    }

    public interface OnMessagesSynchronizedListener {
        // Этот метод сработает, когда отправка завершится и база обновится
        void onSynchronized(MessageDto updatedMessages);

        void onError(Exception e);
    }

    // Сетевая SMTP отправка на фоновом потоке
    public boolean sendEmailOverNetwork(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, OnMessagesSynchronizedListener listener) {

        if (listener == null) {
            Log.e("EmailClient.sendEmailOverNetwork listener", "Критическая ошибка listener == null");
            return false;
        }

        // Handler нужен, чтобы вернуть результат в Главный (UI) поток Android
        var mainHandler = new Handler(Looper.getMainLooper());

        networkExecutor.execute(() -> {
            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, "my@mail.ru");
            try {
                // 💥 ЗАЩИТА: Мгновенно бронируем статус сообщения перед выходом в сеть по SMTP!
                dbService.markMessageAsSending(localMessageId);

                // Получаем готовую SMTP сессию
                Session session = createSmtpSession(prefs);

                var message = new MimeMessage(session);
                message.setFrom(new InternetAddress(myEmail));
                message.setRecipients(TO, InternetAddress.parse(recipientEmail));
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
                MessageDto freshlyLoadedMessages = dbService.getMessage(localMessageId);

                mainHandler.post(() -> listener.onSynchronized(freshlyLoadedMessages));
            } catch (AuthenticationFailedException e) {
                // Сбой! Сбрасываем статус обратно в 0, чтобы фоновый ретрай подхватил его через 20 секунд
                dbService.resetMessageStatusToUnsent(localMessageId);
                // Неверный пароль. Прерываем отправку всей пачки, так как данные авторизации сломаны.
                Log.e("MailLis_Retry", "Критическая ошибка: неверный пароль для " + myEmail);
                mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель

            } catch (MessagingException e) {
                // Любая другая ошибка (чаще всего — пропала сеть).
                // Оставляем сообщение со статусом 0 и выходим из цикла до следующего тика таймера.
                // Сбой! Сбрасываем статус обратно в 0, чтобы фоновый ретрай подхватил его через 20 секунд
                dbService.resetMessageStatusToUnsent(localMessageId);
                Log.w("MailLis_Retry", "Сбой отправки сообщения , попробуем позже. Причина: " + e.getMessage()); //+ message.getId() + ", попробуем позже. Причина: " + e.getMessage());
                mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель
            } catch (Exception e) {
                // Сбой! Сбрасываем статус обратно в 0, чтобы фоновый ретрай подхватил его через 20 секунд
                dbService.resetMessageStatusToUnsent(localMessageId);
                Log.e("MailLis_Retry", "Сбой отправки сообщения Причина: " + e.getMessage());
                mainHandler.post(() -> listener.onError(e)); // Передаем ошибку в слушатель
            }
        });
        return true;
    }


    /**
     * Инициализирует P2P-рукопожатие: сохраняет техническое сообщение в базу данных
     * и асинхронно отправляет ваш публичный ключ Base64 собеседнику по SMTP.
     */
    public boolean sendHandshakeRequest(String targetEmail, Context context, long chatId, SharedPreferences prefs) {
        try {
            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, "my@mail.ru");
            long currentTimestamp = System.currentTimeMillis();
            String myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();

            // 1. Сохраняем служебное сообщение в локальную SQLite историю
            // Метод saveMessage сам выставит нужные флаги (is_mine = 1, is_sent = 0)
            int localMessageId = dbService.saveGenericMessage(
                    (int) chatId,
                    myEmail,
                    KEY_REQUEST, // Тема письма "[MailLis-Key-Request]"
                    myPublicKeyBase64,   // В тело письма прячем публичный ключ устройства
                    true,
                    currentTimestamp
            );

            // 2. Запускаем асинхронную SMTP отправку через наш оптимизированный метод
            sendEmailOverNetwork(
                    chatId,
                    targetEmail,
                    KEY_REQUEST,
                    myPublicKeyBase64,
                    localMessageId, // Передаем ID для последующего обновления server_uid и статуса ретрая
                    prefs,
                    new OnMessagesSynchronizedListener() {
                        @Override
                        public void onSynchronized(MessageDto updatedMessageDto) {
                            // Сеть отработала успешно, сообщение улетело на Mail.ru
                            if (updatedMessageDto != null) {
                                Log.d("MailLis_Handshake", "Запрос рукопожатия успешно отправлен в сеть на адрес: " + targetEmail);
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            // Ошибка сети или авторизации — сработает наша логика ретрая,
                            // сообщение останется со статусом is_sent = 0 в SQLite
                            Log.e("MailLis_Handshake", "Сбой отправки рукопожатия, отложено в очередь ретрая: " + e.getMessage());
                        }
                    }
            );

            return true;

        } catch (Exception e) {
            Log.e("MailLis_Handshake", "Критическая ошибка внутри метода sendHandshakeRequest", e);
            return false;
        }
    }

    /**
     * Синхронно отправляет ответный публичный ключ собеседника (Handshake Response)
     * и возвращает сгенерированный сервером Mail.ru идентификатор сообщения (serverUid).
     * <p>
     * ВНИМАНИЕ: Вызывать строго из фонового потока!
     */
    public String sendHandshakeResponse(String targetEmail, long chatId, SharedPreferences prefs) {
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, "my@mail.ru");
        String myPublicKeyBase64;
        int localMessageId;

        try {
            myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            return EMPTI_STRING;
        }

        long currentTimestamp = System.currentTimeMillis();
        // 1. Сохраняем служебное сообщение в локальную SQLite историю
        localMessageId = dbService.saveGenericMessage(
                (int) chatId,
                myEmail,
                KEY_RESPONSE,
                myPublicKeyBase64,
                true,
                currentTimestamp
        );

        // 💥 ЗАЩИТА: Мгновенно бронируем статус сообщения перед выходом в сеть по SMTP!
        dbService.markMessageAsSending(localMessageId);

        // 5. БЕЗОПАСНО ИЗВЛЕКАЕМ Message-ID сервера Mail.ru
        String serverUid = sendEmailOverNetworkSync(chatId, targetEmail, KEY_RESPONSE,
                myPublicKeyBase64, localMessageId, prefs);
        if (serverUid != null && serverUid.isEmpty()) {
            // Обновляем ID и статус успеха в локальной базе данных SQLite
            dbService.updateServerUid(serverUid, localMessageId);
            dbService.markMessageAsSent(localMessageId, serverUid);

            Log.d("MailLis_Handshake", "Ответный ключ синхронно отправлен. UID: " + serverUid);
            // Возвращаем долгожданный UID обратно
            return serverUid;
        }
        // Если произошла ошибка, возвращаем дефолтную пустую строку во избежание крэша
        return EMPTI_STRING;
    }

    // Фоновый метод для удаления письма по протоколу IMAP
    public void deleteMessageFromServer(final String serverUid, android.content.SharedPreferences prefs) {
        if (serverUid == null || serverUid.isEmpty()) {
            return;
        }

        new Thread(() -> {
            Store store;
            Folder inbox;
            try {
                store = connectToImapServer(prefs);
                inbox = store.getFolder(INBOX);
                inbox.open(READ_WRITE);

                // КРИТИЧЕСКАЯ ОПТИМИЗАЦИЯ: Создаем поисковый терм для Message-ID
                SearchTerm searchTerm = new MessageIDTerm(serverUid);

                // Сервер выполняет поиск у себя и возвращает массив (там будет строго 0 или 1 элемент)
                Message[] foundMessages = inbox.search(searchTerm);

                if (foundMessages != null && foundMessages.length > 0) {
                    Log.d("MailLis_IMAP_Delete", "Сообщение успешно найдено на сервере по UID: " + serverUid);
                    deleteMessage(foundMessages[0], inbox);
                } else {
                    Log.w("MailLis_IMAP_Delete", "Сообщение с UID " + serverUid + " не найдено на сервере.");
                }

                // Закрываем с флагом true для применения удаления (Expunge)
                inbox.close(true);
                store.close();

            } catch (Exception e) {
                Log.e("MailLis_Delete", Objects.requireNonNull(e.getMessage()));
            }
        }).start();
    }

    public void retryUnsentMessages(SharedPreferences prefs) {

        // ИСПОЛЬЗУЕМ ТОТ ЖЕ САМЫЙ ЕДИНЫЙ ПУЛ (networkExecutor)
        networkExecutor.execute(() -> {

            // 1. Просто запрашиваем у сервиса БД список всех неотправленных писем
            List<MessageDto> unsentMessageDtos = dbService.getUnsentMessages();

            if (unsentMessageDtos.isEmpty()) {
                return; // Если очередь пуста, тихо выходим
            }

            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
            if (myEmail.isEmpty()) {
                return;
            }

            // 2. Бежим в цикле по готовым объектам сообщений
            for (MessageDto msg : unsentMessageDtos) {
                try {
                    // Вытаскиваем chatId для этого сообщения (нужно будет получить recipientEmail)
                    int chatId = dbService.getChatIdByMessageId(msg.getId());

                    // 💥 ЗАЩИТА: Мгновенно бронируем сообщение в SQLite, переводя статус в 3!
                    // Теперь никакой другой поток (например, кнопка) не отправит дубликат.
                    dbService.markMessageAsSending(msg.getId());

                    if (msg.isGroup()) {

                        if (IMAGE_MSG.equals(msg.getMessageType())) {
                            String filePath = msg.getFilePath();

                            // Проверяем физическое наличие картинки, если тип IMAGE
                            if (filePath == null || !new File(filePath).exists()) {
                                Log.e("MailLis_Retry_Media", "Файл картинки удален с диска. Ретрай невозможен для ID: " + msg.getId());
                                dbService.resetMessageStatusToUnsent(msg.getId());
                                continue;
                            }

                            byte[] rawImageBytes = getRawImageBytes(filePath);

                            sendGroupImageOverNetwork(chatId, rawImageBytes, msg.getId(), prefs);
                        } else {

                            // КАНАЛ РЕТРАЯ А: ГРУППОВОЕ СООБЩЕНИЕ (Веерный SMTP + AES-GCM)
                            byte[] groupSecretKey = dbService.getGroupSecretKey(chatId);

                            if (groupSecretKey != null && groupSecretKey.length > 0) {
                                // Заново шифруем сырой текст сообщения ключом группы
                                byte[] encryptedBytes = CryptoServiceImpl.encryptGroupText(msg.getText(), groupSecretKey);
                                String base64GroupText = Base64.encodeToString(encryptedBytes, NO_WRAP);

                                // Вызываем наш веерный SMTP метод
                                sendGroupMessageOverNetwork(chatId, msg.getSubject(), base64GroupText, msg.getId(), prefs);
                                Log.d("MailLis_Retry", "Групповое сообщение " + msg.getId() +
                                        " успешно отправлено в очередь ретрая.");
                            } else {
                                dbService.resetMessageStatusToUnsent(msg.getId());
                            }
                        }
                        continue;
                    }
                    if (IMAGE_MSG.equals(msg.getMessageType())) {
                        String filePath = msg.getFilePath();

                        // Проверяем физическое наличие картинки, если тип IMAGE
                        if (filePath == null || !new File(filePath).exists()) {
                            Log.e("MailLis_Retry_Media", "Файл картинки удален с диска. Ретрай невозможен для ID: " + msg.getId());
                            dbService.resetMessageStatusToUnsent(msg.getId());
                            continue;
                        }

                        byte[] rawImageBytes = getRawImageBytes(filePath);

                        String recipientEmail = dbService.getRecipientEmailByChatId(chatId);

                        sendImageOverNetwork(chatId, recipientEmail, rawImageBytes, msg.getId(), prefs, new OnMessagesSynchronizedListener() {
                            @Override
                            public void onSynchronized(MessageDto updatedMessages) {
                                // Сеть прошла успешно! Переводим часики (0) в одну галочку (1)
                                Log.d("MailLis_Retry", "Сообщение с ID " + msg.getId() +
                                        " успешно переотправлено после восстановления сети. UID: " +
                                        msg.getServerUid());
                            }

                            @Override
                            public void onError(Exception e) {
                                // Ошибка сети или авторизации — сработает наша логика ретрая,
                                // сообщение останется со статусом is_sent = 0 в SQLite
                                Log.e("MailLis_Retry", "Сбой переотправки сообщения, отложено в очередь ретрая: "
                                        + e.getMessage());
                                dbService.resetMessageStatusToUnsent(msg.getId());
                            }
                        });
                    } else {
                        String recipientEmail = dbService.getRecipientEmailByChatId(chatId);
                        if (recipientEmail == null || recipientEmail.isEmpty()) {
                            continue;
                        }
                        String sendTxt = msg.getText();
                        try {
                            byte[] sharedSecret = dbService.getPublicKey(chatId);
                            sendTxt = sharedSecret != null ? cryptoService.encrypt(sendTxt, sharedSecret) : sendTxt;
                        } catch (GeneralSecurityException | IOException e) {
                            Log.e("MailLis_Retry", "Ошибка шифрования при повторно отправке");
                            throw new RuntimeException(e); //TODO добавить свой эквепшн
                        }
                        // Переотправляем письмо по SMTP
                        sendEmailOverNetwork(chatId, recipientEmail, msg.getSubject(), sendTxt, msg.getId(), prefs,
                                new OnMessagesSynchronizedListener() {
                                    @Override
                                    public void onSynchronized(MessageDto updatedMessageDto) {
                                        // Сеть прошла успешно! Переводим часики (0) в одну галочку (1)
                                        Log.d("MailLis_Retry", "Сообщение с ID " + msg.getId() +
                                                " успешно переотправлено после восстановления сети. UID: " +
                                                updatedMessageDto.getServerUid());
                                    }

                                    @Override
                                    public void onError(Exception e) {
                                        // Ошибка сети или авторизации — сработает наша логика ретрая,
                                        // сообщение останется со статусом is_sent = 0 в SQLite
                                        Log.e("MailLis_Retry", "Сбой переотправки сообщения, отложено в очередь ретрая: "
                                                + e.getMessage());
                                        dbService.resetMessageStatusToUnsent(msg.getId());
                                    }
                                });
                    }

                } catch (Exception e) {
                    // Если сеть оборвалась во время ретрая, возвращаем статус обратно в 0,
                    // чтобы система попробовала отправить его на следующем тике таймера.
                    dbService.resetMessageStatusToUnsent(msg.getId());
                    // Если интернета всё еще нет — SMTP выкинет ошибку.
                    // Прерываем отправку пачки до следующего тика таймера (через 20 секунд)
                    Log.w("MailLis_Retry", "Ретрай не удался, сеть по-прежнему недоступна: " + e.getMessage());
                    break;
                }
            }

        });
    }

    @NonNull
    private static byte[] getRawImageBytes(String filePath) throws IOException {
        //  Используем FileInputStream вместо Files.readAllBytes
        File file = new File(filePath);
        byte[] rawImageBytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            int numRead;
            while (offset < rawImageBytes.length &&
                    (numRead = fis.read(rawImageBytes, offset, rawImageBytes.length - offset)) >= 0) {
                offset += numRead;
            }
        }
        return rawImageBytes;
    }

    @Override
    public String sendEmailOverNetworkSync(long chatId, String recipientEmail, String subject, String messageText,
                                           int localMessageId, SharedPreferences prefs) {
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
        String generatedServerUid;
        try {
            // ИСПОЛЬЗУЕМ ВЫДЕЛЕННЫЙ МЕТОД: Получаем готовую SMTP сессию
            Session session = createSmtpSession(prefs);

            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(myEmail));
            message.setRecipients(TO, InternetAddress.parse(recipientEmail));
            message.setSubject(subject);
            message.setText(messageText);
            // ПРИНУДИТЕЛЬНО ПРИМЕНЯЕМ ИЗМЕНЕНИЯ, чтобы сгенерировался Message-ID
            message.saveChanges();
            // Отправляем в сеть
            Transport.send(message);

            // БЕЗОПАСНО ИЗВЛЕКАЕМ Message-ID
            // Используем встроенный метод вместо getHeader()
            generatedServerUid = message.getMessageID();
        } catch (AuthenticationFailedException e) {
            // Неверный пароль. Прерываем отправку всей пачки, так как данные авторизации сломаны.
            Log.e("MailLis_Retry", "Критическая ошибка: неверный пароль для " + myEmail);
            return null;

        } catch (MessagingException e) {
            // Любая другая ошибка (чаще всего — пропала сеть).
            // Оставляем сообщение со статусом 0 и выходим из цикла до следующего тика таймера.
            Log.w("MailLis_Retry", "Сбой отправки сообщения , попробуем позже. Причина: "
                    + e.getMessage()); //+ message.getId() + ", попробуем позже. Причина: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.e("MailLis_Retry", "Сбой отправки сообщения Причина: " + e.getMessage());
            return null;
        }
        return generatedServerUid;
    }

    public void sendImageOverNetwork(long chatId, String recipientEmail, byte[] rawImageBytes,
                                     final int localMessageId, SharedPreferences prefs, OnMessagesSynchronizedListener listener) {
        networkExecutor.execute(() -> {
            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
            try {
                dbService.markMessageAsSending(localMessageId); // Бронируем статус 3 (в процессе)

                byte[] peerPublicKeyBytes = dbService.getPublicKey(chatId);

                // Шифруем бинарный массив фотографии асимметричным ключом Tink
                byte[] encryptedImageBytes = cryptoService.encryptBytes(rawImageBytes, peerPublicKeyBytes);

                // Превращаем зашифрованный бинарник в безопасную для почты Base64-строку
                String base64EncryptedImage = Base64.encodeToString(encryptedImageBytes, NO_WRAP);

                // СБОРКА MIME ПИСЬМА С ВЛОЖЕНИЕМ
                Session session = createSmtpSession(prefs);
                var message = new MimeMessage(session);
                message.setFrom(new InternetAddress(myEmail));
                message.setRecipients(TO, InternetAddress.parse(recipientEmail));
                message.setSubject(CHAT_IMAGE_MAIL); // Специальный служебный заголовок

                long currentTimestamp = System.currentTimeMillis();
                var multipart = getMimeMultipart(currentTimestamp, base64EncryptedImage);

                message.setContent(multipart);
                message.saveChanges();

                // ОТПРАВКА
                Transport.send(message);

                String generatedServerUid = message.getMessageID();
                dbService.markMessageAsSent(localMessageId, generatedServerUid);
                dbService.updateServerUid(generatedServerUid, localMessageId);

                MessageDto freshMessages = dbService.getMessage(chatId);
                new Handler(Looper.getMainLooper()).post(() -> listener.onSynchronized(freshMessages));

            } catch (Exception e) {
                dbService.resetMessageStatusToUnsent(localMessageId);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e));
            }
        });
    }

    public void sendGroupImageOverNetwork(long chatId, byte[] rawImageBytes, int localMessageId, SharedPreferences prefs) {
        networkExecutor.execute(() -> {
            try {
                dbService.markMessageAsSending(localMessageId);
                String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);


                byte[] groupSecretKey = dbService.getGroupSecretKey(chatId);

                // Шифруем текст сообщения общим AES-ключом группы
                byte[] encryptedGroupBytes = CryptoServiceImpl.encryptGroupBytes(rawImageBytes, groupSecretKey);
                String base64EncryptedImage = Base64.encodeToString(encryptedGroupBytes, NO_WRAP);

                // Получаем всех участников этой группы из SQLite
                List<String> recipients = dbService.getGroupMembers(chatId, myEmail);
                Session session = createSmtpSession(prefs);


                // Склеиваем список участников в одну строку через запятую для заголовка
                StringBuilder membersSb = new StringBuilder(myEmail); // Начинаем со своего email
                for (String email : recipients) {
                    membersSb.append(",").append(email);
                }
                String groupComposition = membersSb.toString();

                String generatedServerUid = EMPTI_STRING;

                recipients = recipients
                        .stream()
                        .filter(e -> !e.equals(myEmail))
                        .collect(Collectors.toList());

                // ВЕЕРНАЯ РАССЫЛКА: Шлем копию письма каждому участнику отдельно
                for (String recipientEmail : recipients) {
                    var message = new MimeMessage(session);
                    message.setFrom(new InternetAddress(myEmail));
                    message.setRecipients(TO, InternetAddress.parse(recipientEmail));

                    // Передаем ID чата в теме письма, чтобы получатели знали, в какую именно группу положить сообщение!
                    message.setSubject(GROUP_IMAGE_MAIL + SPACE_STRING + chatId);
                    long currentTimestamp = System.currentTimeMillis();
                    var multipart = getMimeMultipart(currentTimestamp, base64EncryptedImage);

                    message.setContent(multipart);
                    message.addHeader("X-MailLis-Group-Members", groupComposition);
                    message.saveChanges();

                    Transport.send(message);

                    generatedServerUid = message.getMessageID();
                }

                // Помечаем в локальной базе как отправлено (хотя бы сервером)
                dbService.markMessageAsSent(localMessageId, generatedServerUid);

            } catch (Exception e) {
                dbService.resetMessageStatusToUnsent(localMessageId);
                e.printStackTrace();
            }
        });
    }

    @NonNull
    private static MimeMultipart getMimeMultipart(long currentTimestamp, String base64EncryptedImage) throws MessagingException {
        String localFileName = "encrypted_photo_" + currentTimestamp + ".lis";
        // Помещаем зашифрованную Base64 строку в тело вложения
        var imagePart = new MimeBodyPart();
        imagePart.setText(base64EncryptedImage);
        imagePart.setFileName(localFileName);

        // Создаем Multipart контейнер
        var multipart = new MimeMultipart();
        multipart.addBodyPart(imagePart);
        return multipart;
    }


    public void sendGroupMessageOverNetwork(long chatId, String subject, String cipherTextBase64, int localMessageId, SharedPreferences prefs) {
        networkExecutor.execute(() -> {
            try {
                dbService.markMessageAsSending(localMessageId);
                String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

                // Получаем всех участников этой группы из SQLite
                List<String> recipients = dbService.getGroupMembers(chatId, myEmail);

                // Склеиваем список участников в одну строку через запятую для заголовка
                StringBuilder membersSb = new StringBuilder(myEmail); // Начинаем со своего email
                for (String email : recipients) {
                    membersSb.append(",").append(email);
                }
                String groupComposition = membersSb.toString();

                Session session = createSmtpSession(prefs);

                String generatedServerUid = EMPTI_STRING;

                recipients = recipients
                        .stream()
                        .filter(e -> !e.equals(myEmail))
                        .collect(Collectors.toList());

                // ВЕЕРНАЯ РАССЫЛКА: Шлем копию письма каждому участнику отдельно
                for (String recipientEmail : recipients) {
                    var message = new MimeMessage(session);
                    message.setFrom(new InternetAddress(myEmail));
                    message.setRecipients(TO, InternetAddress.parse(recipientEmail));

                    // Передаем ID чата в теме письма, чтобы получатели знали, в какую именно группу положить сообщение!
                    message.setSubject(subject);
                    message.setText(cipherTextBase64);
                    // КРИТИЧЕСКИЙ ФИКС: Передаем весь состав группы прямо внутри метаданных заголовка письма!
                    message.addHeader("X-MailLis-Group-Members", groupComposition);
                    message.saveChanges();

                    Transport.send(message);

                    generatedServerUid = message.getMessageID();
                }

                // Помечаем в локальной базе как отправлено (хотя бы сервером)
                dbService.markMessageAsSent(localMessageId, generatedServerUid);

            } catch (Exception e) {
                Log.e("MailLis_GroupNet", "Не удалось доставить пакет");
                dbService.resetMessageStatusToUnsent(localMessageId);
                e.printStackTrace();
            }
        });
    }

    /**
     * Безопасно рассылает секретный AES-ключ группы участнику.
     * Ключ шифруется индивидуальным публичным Tink-ключом получателя.
     */
    public void sendGroupInviteOverNetwork(String recipientEmail, long chatId, String groupName, byte[] groupSecretKey, SharedPreferences prefs) {
        networkExecutor.execute(() -> {
            try {
                String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
                Session session = createSmtpSession(prefs);

                // Проверяем, есть ли у нас публичный ключ Tink этого друга
                byte[] peerPublicKeyBytes = dbService.getPublicKeyByEmail(recipientEmail);

                // Получаем всех участников этой группы из SQLite
                List<String> recipients = dbService.getGroupMembers(chatId, myEmail);

                // Склеиваем список участников в одну строку через запятую для заголовка
                StringBuilder membersSb = new StringBuilder(myEmail); // Начинаем со своего email
                for (String email : recipients) {
                    membersSb.append(",").append(email);
                }
                String groupComposition = membersSb.toString();

                var message = new MimeMessage(session);
                message.setFrom(new InternetAddress(myEmail));
                message.setRecipients(TO, InternetAddress.parse(recipientEmail));

                if (peerPublicKeyBytes != null && peerPublicKeyBytes.length > 5) {
                    // ВАРИАНТ А: Ключ друга есть! Шлем финальный ЗАШИФРОВАННЫЙ инвайт с ключом группы
                    byte[] encryptedKeyBytes = cryptoService.encryptBytes(groupSecretKey, peerPublicKeyBytes);
                    String base64EncryptedKey = Base64.encodeToString(encryptedKeyBytes, NO_WRAP);

                    message.setSubject(GROUP_INVITE_MAIL + SPACE_STRING + chatId);
                    message.setText(groupName + "\r\n" + base64EncryptedKey);

                    Log.d("MailLis_Group", "Отправлен финальный защищенный инвайт для: " + recipientEmail);
                } else {
                    // ВАРИАНТ Б: Ключа друга нет! Шлем открытый Pre-Invite и прикрепляем СВОЙ публичный ключ
                    String myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();

                    message.setSubject(GROUP_PRE_INVITE_MAIL + SPACE_STRING + chatId);
                    message.setText(groupName + "\r\n" + myPublicKeyBase64);

                    Log.w("MailLis_Group", "Ключа друга нет. Отправлен безопасный Pre-Invite для: " + recipientEmail);
                }

                message.addHeader("X-MailLis-Group-Members", groupComposition);
                message.saveChanges();

                Transport.send(message);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void sendGroupPreInviteResponse(String creatorEmail, long chatId, SharedPreferences prefs) {
        networkExecutor.execute(() -> {
            try {
                String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
                Session session = createSmtpSession(prefs);

                var message = new MimeMessage(session);
                message.setFrom(new InternetAddress(myEmail));
                message.setRecipients(TO, InternetAddress.parse(creatorEmail));

                // Сигнализируем создателю группы, возвращая наш публичный ключ Tink
                message.setSubject(GROUP_PRE_INVITE_RESPONSE_MAIL + SPACE_STRING + chatId);
                String myPublicKeyBas64 = cryptoService.getMyPublicKeyBas64();

                message.setText(myPublicKeyBas64);
                message.saveChanges();

                Transport.send(message);

                Log.d("MailLis_Group", "Ответ на Pre-Invite успешно отправлен создателю: " + creatorEmail);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }


    public void sendGroupSyncNotification(String oldMemberEmail, long groupId, String newMemberEmail, SharedPreferences prefs) throws Exception {
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
        Session session = createSmtpSession(prefs);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(prefs.getString(KEY_EMAIL_LOGIN, "")));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(oldMemberEmail));

        message.setSubject("[MailLis-Group-Sync] " + groupId);
        message.setText(newMemberEmail); // Передаем email новичка, которого нужно дописать в базу
        message.saveChanges();

        Transport.send(message);
    }

    private Store connectToImapServer(SharedPreferences prefs) throws MessagingException {
        final String username = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
        final String password = prefs.getString(KEY_EMAIL_PASSWORD, EMPTI_STRING);
        String imapServer = prefs.getString(KEY_IMAP_SERVER, "imap.mail.ru");
        String imapPort = prefs.getString(KEY_IMAP_PORT, "993");

        if (username.isEmpty() || password.isEmpty()) {
            throw new AuthenticationFailedException("Логин или пароль для Mail.ru не заполнены.");
        }

        var props = getPropertiesImap(imapServer, imapPort);

        Session session = Session.getInstance(props, null);
        Store store = session.getStore("imaps");
        store.connect(imapServer, username, password);

        return store;
    }

    @NonNull
    private static Properties getPropertiesImap(String imapServer, String imapPort) {
        var props = new Properties();
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
        return props;
    }

    private Session createSmtpSession(SharedPreferences prefs) throws MessagingException {
        final String username = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
        final String password = prefs.getString(KEY_EMAIL_PASSWORD, EMPTI_STRING);
        String smtpServer = prefs.getString(KEY_SMTP_SERVER, "smtp.mail.ru");
        String smtpPort = prefs.getString(KEY_SMTP_PORT, "465");

        if (username.isEmpty() || password.isEmpty()) {
            throw new AuthenticationFailedException("Логин или пароль для SMTP Mail.ru не заполнены.");
        }

        var props = new Properties();
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

    private boolean deleteMessage(Message message, Folder inbox) throws MessagingException {

        String messageId = extractMsgId(message);
        if (messageId.isEmpty()) {
            return false;
        }

        // Создаем массив из одного элемента и принудительно ставим флаг удаления через папку
        var messageArray = new Message[]{message};
        inbox.setFlags(messageArray, new Flags(DELETED), true);
        // 2. ДОБАВИТЬ СТРОКУ: Принудительно заставляем IMAP-сервер физически стереть помеченные сообщения прямо сейчас
        inbox.expunge();
        Log.d("MailLis_Delete", "Письмо с Message-ID успешно найдено и удалено.");
        return true;
    }

    private String extractMsgId(Message message) throws MessagingException {
        String[] mimeIds = message.getHeader("Message-ID");
        String messageId = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;
        if (messageId == null) {
            return EMPTI_STRING;
        }
        return messageId;
    }

}
