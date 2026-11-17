package ru.toboe512.maillis.service;

import static android.util.Base64.DEFAULT;
import static android.util.Base64.NO_WRAP;
import static ru.toboe512.maillis.consts.Consts.CHAT_FILE;
import static ru.toboe512.maillis.consts.Consts.CHAT_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.CHAT_MESSAGE;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.FILE_MSG;
import static ru.toboe512.maillis.consts.Consts.GROUP_CHAT_FILE;
import static ru.toboe512.maillis.consts.Consts.GROUP_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_INVITE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_LEAVE;
import static ru.toboe512.maillis.consts.Consts.GROUP_MESSAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_PRE_INVITE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_PRE_INVITE_RESPONSE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_SYNC;
import static ru.toboe512.maillis.consts.Consts.IMAGE_MSG;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.consts.Consts.KEY_REQUEST;
import static ru.toboe512.maillis.consts.Consts.KEY_RESPONSE;
import static ru.toboe512.maillis.consts.Consts.READ_RECEIPT;
import static ru.toboe512.maillis.consts.Consts.SPACE_STRING;
import static ru.toboe512.maillis.consts.Consts.TEXT_MSG;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.NetworkMessageDto;
import ru.toboe512.maillis.dto.MessageDto;

public class MailExecutorServiceImpl implements MailExecutorService {

    // Переменные для работы с базой данных
    private final DBService dbService;

    private final CryptoService cryptoService;

    // ГЛОБАЛЬНЫЙ ЕДИНЫЙ ПУЛ ПОТОКОВ ДЛЯ ВСЕЙ СЕТИ ПРИЛОЖЕНИЯ
    private final ExecutorService networkExecutor;

    private final EmailClient emailClient;

    public interface OnMessagesSynchronizedListener {
        // Этот метод сработает, когда отправка завершится и база обновится
        void onSynchronized(MessageDto updatedMessages);

        void onError(Exception e);
    }

    private static class Holder {
        private static MailExecutorService INSTANCE;
    }

    public static MailExecutorService getInstance(EmailClient emailClient, DBService dbService, Context context) {
        if (MailExecutorServiceImpl.Holder.INSTANCE == null) {
            MailExecutorServiceImpl.Holder.INSTANCE = new MailExecutorServiceImpl(emailClient, dbService, context);
        }
        return MailExecutorServiceImpl.Holder.INSTANCE;
    }

    private MailExecutorServiceImpl(EmailClient emailClient, DBService dbService, Context context) {
        this.dbService = dbService;
        this.emailClient = emailClient;
        // Создаем ОДИН поток на всё приложение. Задачи будут выполняться строго друг за другом!
        this.networkExecutor = Executors.newSingleThreadExecutor();
        try {
            this.cryptoService = new CryptoServiceImpl(context);
        } catch (GeneralSecurityException | IOException e) {
            Log.e("MailExecutorServiceImpl", "Ошибка инициализациии CryptoServiceImpl", e);
            throw new RuntimeException(e);
        }
    }

    public int fetchIncomingOverNetwork(Context context, SharedPreferences prefs) {
        int result = 0;
        try {
            List<NetworkMessageDto> messages = emailClient.fetch(prefs);
            for (NetworkMessageDto msg : messages) {
                String subject = msg.getSubject();
                String senderEmail = msg.getFrom();

                // ПРОВЕРКА НА БАН: Если отправитель в черном списке — стираем письмо и игнорируем!
                if (dbService.isUserBanned(senderEmail)) {
                    Log.w("MailLis_Security", "🛑 Заблокированный спам от " + senderEmail + " проигнорирован.");
                    emailClient.deleteMessage(msg.getId(), prefs);
                    continue; // Мгновенно переходим к следующему письму, этот объект дальше не обрабатываем!
                }
                // СЛУЧАЙ В: Прилетело обычное текстовое сообщение чата
                if (subject.contains(CHAT_MESSAGE)) {
                    result = result + chatMessageCase(msg, prefs);
                    continue;
                }
                if (subject.contains(CHAT_IMAGE_MAIL)) {
                    result = result + chatImageCase(msg, context.getCacheDir(), prefs);
                    continue;
                }
                if (subject.contains(GROUP_MESSAGE_MAIL)) {
                    result = result + groupMessageCase(msg, prefs);
                    continue;
                }
                if (subject.contains(GROUP_IMAGE_MAIL)) {
                    result = result + groupImageCase(msg, context.getCacheDir(), prefs);
                    continue;
                }
                if (subject.contains(READ_RECEIPT)) {
                    result = result + readReceipt(msg, prefs);
                    continue;
                }
                if (subject.contains(GROUP_LEAVE)) {
                    result = result + groupLeave(msg, prefs);
                    continue;
                }
                if (subject.contains(CHAT_FILE)) {
                    result = result + chatFile(msg, context.getCacheDir(), prefs);
                    continue;
                }
                if (subject.contains(GROUP_CHAT_FILE)) {
                    result = result + groupChatFile(msg, context.getCacheDir(), prefs);
                    continue;
                }
                String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
                //Далее блок ловли хендшейка свои майлы нам не интересны
                if (senderEmail.contains(myEmail)) {
                    continue;
                }
                //  В СУЩЕСТВУЮЩУЮ ГРУППУ ДОБАВИЛСЯ КТО-ТО ЕЩЕ (Синхронизация состава)
                if (subject.contains(GROUP_SYNC)) {
                    result = result + groupSync(msg, prefs);
                    continue;
                }
                // СЦЕНАРИЙ 1: Нам прилетел Pre-Invite (Мы получатель, у нас просят ключ для вступления)
                if (subject.contains(GROUP_PRE_INVITE_MAIL)) {
                    result = result + groupPreInviteCase(msg, prefs);
                    continue;
                }
                // СЦЕНАРИЙ 2: Создателю группы прилетел ответный Tink-ключ от друга
                if (subject.contains(GROUP_PRE_INVITE_RESPONSE_MAIL)) {
                    result = result + groupPreInviteResponseCase(msg, prefs);
                    continue;
                }
                // СЦЕНАРИЙ 3: Получателю прилетел долгожданный ЗАШИФРОВАННЫЙ AES-ключ группы
                if (subject.contains(GROUP_INVITE_MAIL)) {
                    result = result + groupInviteCase(msg, prefs);
                    continue;
                }
                // СЛУЧАЙ А: Прилетел запрос на создание чата и обмен ключами
                if (subject.contains(KEY_REQUEST)) {
                    result = result + keyRequestCase(msg, prefs);
                    continue;
                }
                // СЛУЧАЙ Б: Прилетел ответный ключ (собеседник подтвердил создание нашего чата)
                if (subject.contains(KEY_RESPONSE)) {
                    result = result + keyResponseCase(msg, prefs);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_P2P_fetchIncoming", "Ошибка обновления ", e);
        }
        return result;
    }

    private int chatMessageCase(NetworkMessageDto msg, SharedPreferences prefs) throws IOException, GeneralSecurityException {
        int result = 0;
        String senderEmail = msg.getFrom();
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

        if (senderEmail.equals(myEmail)) {
            return 0;
        }

        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        if (chatId <= 0) {
            // СТРОГОЕ ПРАВИЛО: Если один из пользователей удалил чат,
            // обычные сообщения от него просто тихо стираются с сервера и игнорируются!
            Log.w("MailLis_P2P", "Чат удален или не создан. Сообщение от " + senderEmail + " проигнорировано.");
            return 0;
        }
        // Если чат существует у обоих — сохраняем сообщение в базу
        String messageText = msg.getContent();
        byte[] sharedSecret = dbService.getPublicKey(chatId);

        messageText = sharedSecret != null ? cryptoService.decrypted(messageText) : messageText;

        String messageId = msg.getId();
        if (dbService.saveIncomingMessage(chatId, senderEmail, CHAT_MESSAGE,
                messageText, msg.getTimestamp(), messageId, TEXT_MSG, EMPTI_STRING)) {
            result = 1;
            emailClient.deleteMessage(msg.getId(), prefs);
        }

        // СРАЗУ ШЛЕМ СИГНАЛ ПРОЧТЕНИЯ: Отправляем скрытое SMTP письмо обратно другу
        // Отправляем пустой текст, главное — тема [MailLis-Read-Receipt] и Message-ID в теле или теме
        sendEmailOverNetwork(senderEmail, READ_RECEIPT, messageId, 0, prefs, new MailExecutorServiceImpl.OnMessagesSynchronizedListener() {
            @Override
            public void onSynchronized(MessageDto updatedMessages) {
                Log.d("MailLis_Status", "Отчет о прочтении (✓✓) отправлен для письма: " + messageId);
            }

            @Override
            public void onError(Exception e) {
                // Если не отправлено ничего страшного
            }
        });

        Log.d("MailLis_P2P", "Новое сообщение успешно добавлено в чат " + chatId);
        return result;
    }

    private int chatImageCase(NetworkMessageDto msg, File cacheDir, SharedPreferences prefs) throws IOException, GeneralSecurityException {

        String senderEmail = msg.getFrom();
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

        if (senderEmail.equals(myEmail)) {
            return 0;
        }

        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        if (chatId <= 0) {
            return 0;
        }

        String base64EncryptedImage = msg.getContent();
        byte[] encryptedBytes = Base64.decode(base64EncryptedImage, NO_WRAP);

        // РАСШИФРОВЫВАЕМ ПРИВАТНЫМ КЛЮЧОМ TINK
        byte[] decryptedBytes = cryptoService.decryptBytes(encryptedBytes);

        // СОХРАНЯЕМ ФАЙЛ В ИЗОЛИРОВАННЫЙ КЭШ ТЕЛЕФОНА
        String fileName = "p2p_img_" + System.currentTimeMillis() + ".jpg";
        File file = new File(cacheDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(decryptedBytes);
        }

        // ЗАПИСЫВАЕМ В SQLite
        // Сохраняем запись в базу данных, указав тип 'IMAGE' и путь к файлу на диске
         if (dbService.saveIncomingMessage(chatId, senderEmail, CHAT_IMAGE_MAIL, EMPTI_STRING,
                        msg.getTimestamp(), msg.getId(), IMAGE_MSG, file.getAbsolutePath())) {
             emailClient.deleteMessage(msg.getId(), prefs);
             return 1;
         }
        return 0;
    }

    private int groupMessageCase(NetworkMessageDto msg, SharedPreferences prefs) throws Exception {
        String senderEmail = msg.getFrom();
        // Парсим ID родительского чата из темы письма
        String[] parts = msg.getSubject().split(SPACE_STRING);
        long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

        if (groupChatId <= 0 || !dbService.isGroupChat(groupChatId)) {
            return 0;
        }

        // АВТО-СИНХРОНИЗАЦИЯ: Достаем служебный заголовок состава участников
        String membersHeader = Optional.ofNullable(msg.getHeaders().get("X-MailLis-Group-Members")).orElse(EMPTI_STRING);

        String[] incomingEmails = membersHeader.split(",");
        for (String email : incomingEmails) {
            // Метод addGroupMember внутри себя сам проверит дубликаты и привяжет к нужному localId!
            dbService.addGroupMember(groupChatId, email.trim().toLowerCase());
        }

        byte[] groupKey = dbService.getGroupSecretKey(groupChatId);
        String base64Text = msg.getContent();
        byte[] encryptedBytes = Base64.decode(base64Text, NO_WRAP);

        // Дешифруем общим симметричным ключом группы
        String decryptedText = CryptoServiceImpl.decryptGroupText(encryptedBytes, groupKey);

        // Сохраняем в messages, привязывая к ID группы
         if (dbService.saveIncomingGroupMessage(groupChatId, senderEmail, GROUP_MESSAGE_MAIL,
                        decryptedText, msg.getTimestamp(), msg.getId(), TEXT_MSG, EMPTI_STRING)) {
             emailClient.deleteMessage(msg.getId(), prefs);
             return 1;
         }
        return  0;
    }

    private int groupImageCase(NetworkMessageDto msg, File cacheDir, SharedPreferences prefs) throws Exception {
        String senderEmail = msg.getFrom();
        // Парсим ID родительского чата из темы письма
        String[] parts = msg.getSubject().split(SPACE_STRING);
        long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

        if (groupChatId <= 0 || !dbService.isGroupChat(groupChatId)) {
            return 0;
        }

        byte[] groupKey = dbService.getGroupSecretKey(groupChatId);

        byte[] encryptedBytes = Base64.decode(msg.getContent(), NO_WRAP);

        // Дешифруем общим симметричным ключом группы
        byte[] decryptedBytes = CryptoServiceImpl.decryptGroupByte(encryptedBytes, groupKey);

        // СОХРАНЯЕМ ФАЙЛ В ИЗОЛИРОВАННЫЙ КЭШ ТЕЛЕФОНА
        String fileName = "p2p_img_" + System.currentTimeMillis() + ".jpg";
        File file = new File(cacheDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(decryptedBytes);
        }

        // ЗАПИСЫВАЕМ В SQLite
        // Сохраняем в messages, привязывая к ID группы
         if (dbService.saveIncomingGroupMessage(groupChatId, senderEmail, GROUP_IMAGE_MAIL,
                        EMPTI_STRING, msg.getTimestamp(), msg.getId(), IMAGE_MSG, file.getAbsolutePath())) {
             emailClient.deleteMessage(msg.getId(), prefs);
             return 1;
         }
        return 0;
    }

    private int readReceipt(NetworkMessageDto msg, SharedPreferences prefs) {
        // 💥 ПОЙМАЛИ ОТЧЕТ О ПРОЧТЕНИИ ЧАТА!
        // В теле этого письма друг прислал нам Message-ID нашего же отправленного ранее сообщения
        String readMessageServerUid = msg.getContent();
        // Обновляем статус сообщения в SQLite на "2" (Две галочки)
        dbService.markMessageAsRead(readMessageServerUid);
        Log.d("MailLis_Status", "Собеседник прочитал наше сообщение! Статус обновлен на ✓✓");

        emailClient.deleteMessage(msg.getId(), prefs);
        return 1;
    }

    private int groupLeave(NetworkMessageDto msg, SharedPreferences prefs) {
        try {
            String[] parts = msg.getSubject().split(SPACE_STRING);
            long remoteGroupId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            // Читаем из тела письма, какой именно Email покинул чат
            String leaverEmail = msg.getContent().toLowerCase();

            if (remoteGroupId > 0 && !leaverEmail.isEmpty()) {
                // Принудительно вырезаем его из SQLite участников и кэша публичных ключей Tink
                dbService.removeGroupMemberByEmail(remoteGroupId, leaverEmail);

                // Локально пишем системное сообщение в чат для уведомления пользователя
                if (dbService.saveIncomingGroupMessage(remoteGroupId, "Система",
                        GROUP_LEAVE, "🏃 Пользователь " + leaverEmail + " покинул чат.",
                        msg.getTimestamp(), msg.getId(), "TEXT", null)) {

                    emailClient.deleteMessage(msg.getId(), prefs);
                    return 1;
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_GroupLeave", "Ошибка приема входящего игнала выхода из чата ", e);
        }
        return 0;
    }

    private int chatFile(NetworkMessageDto msg, File cacheDir, SharedPreferences prefs) {
        try {
            String decryptedPayload = cryptoService.decrypted(msg.getContent()); // Ваш метод дешифровки

            if (decryptedPayload != null && decryptedPayload.contains("::SPLIT::")) {
                String[] payloadParts = decryptedPayload.split("::SPLIT::");
                String fileName = payloadParts[0];      // Оригинальное имя (например, report.pdf)
                String base64Data = payloadParts[1];    // Контент в Base64

                //  Декодируем Base64 обратно в массив байт
                byte[] fileBytes = Base64.decode(base64Data, DEFAULT);

                // Сохраняем в изолированную безопасную внутреннюю память смартфона [source]
                var storageDir = new File(cacheDir, "MailLis_Documents");
                if (!storageDir.exists()) {
                    if (!storageDir.mkdirs()) {
                        return 0;
                    }
                }

                // Создаем файл на диске
                var receivedFile = new File(storageDir, System.currentTimeMillis() + "_" + fileName);
                try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                    fos.write(fileBytes);
                }
                String senderEmail = msg.getFrom();
                long chatId = dbService.getChatIdByRecipientEmail(senderEmail);

                // 5. ЗАПИСЫВАЕМ СТРОКУ В НАШУ ТРАНЗАКЦИОННУЮ СУБД WAL
                // Пишем в message_text имя файла для отображения в бабле, а в file_path — путь к нему на диске!
                if (dbService.saveIncomingMessage(chatId, senderEmail, CHAT_FILE, "📎 Файл: " + fileName, msg.getTimestamp(),
                        msg.getId(), FILE_MSG, receivedFile.getAbsolutePath())) {
                    emailClient.deleteMessage(msg.getId(), prefs);
                    return 1;
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_IMAP_File", "Ошибка приема входящего файла", e);
        }
        return 0;
    }

    private int groupChatFile(NetworkMessageDto msg, File cacheDir, SharedPreferences prefs) {
        try {
            String[] parts = msg.getSubject().split(SPACE_STRING);
            long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            if (groupChatId <= 0 || !dbService.isGroupChat(groupChatId)) {
                return 0;
            }

            byte[] groupSecretKey = dbService.getGroupSecretKey(groupChatId);
            byte[] encryptedBytes = Base64.decode(msg.getContent(), NO_WRAP);

            String decryptedPayload = CryptoServiceImpl.decryptGroupText(encryptedBytes, groupSecretKey); // Ваш метод дешифровки

            if (decryptedPayload != null && decryptedPayload.contains("::SPLIT::")) {
                String[] payloadParts = decryptedPayload.split("::SPLIT::");
                String fileName = payloadParts[0];      // Оригинальное имя (например, report.pdf)
                String base64Data = payloadParts[1];    // Контент в Base64

                // Декодируем Base64 обратно в массив байт
                byte[] fileBytes = Base64.decode(base64Data, DEFAULT);

                // Сохраняем в изолированную безопасную внутреннюю память смартфона [source]
                File storageDir = new File(cacheDir, "MailLis_Documents");
                if (!storageDir.exists()) {
                    if (!storageDir.mkdirs()) {
                        return 0;
                    }
                }

                // Создаем файл на диске
                File receivedFile = new File(storageDir, System.currentTimeMillis() + "_" + fileName);
                try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                    fos.write(fileBytes);
                }


                // ЗАПИСЫВАЕМ СТРОКУ В НАШУ ТРАНЗАКЦИОННУЮ СУБД WAL
                // Пишем в message_text имя файла для отображения в бабле, а в file_path — путь к нему на диске!


                if (dbService.saveIncomingGroupMessage(groupChatId, msg.getFrom(),
                        GROUP_MESSAGE_MAIL, "📎 Файл: " + fileName, msg.getTimestamp(),
                        msg.getId(), FILE_MSG, receivedFile.getAbsolutePath())) {
                    emailClient.deleteMessage(msg.getId(), prefs);
                    return 1;
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_IMAP_File", "Ошибка приема входящего файла", e);
        }
        return 0;
    }

    private int groupSync(NetworkMessageDto msg, SharedPreferences prefs) {
        try {
            String[] parts = msg.getSubject().split(SPACE_STRING);
            long remoteChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;
            String newMemberEmail = msg.getContent().toLowerCase();

            if (remoteChatId > 0 && !newMemberEmail.isEmpty()) {
                // Просто дописываем нового участника в локальную таблицу group_members
                dbService.addGroupMember(remoteChatId, newMemberEmail);

                // Пишем тихое системное уведомление в чат
                if (dbService.saveIncomingGroupMessage(remoteChatId, "Система", "[MailLis-System]",
                        "➕ К группе присоединился участник: " + newMemberEmail,
                        msg.getTimestamp(), msg.getId(), "TEXT", null)) {
                    emailClient.deleteMessage(msg.getId(), prefs);
                    return 1;
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_GroupSync", "Ошибка приема входящего синхро-сообщения ", e);
        }
        return 0;
    }

    private int groupPreInviteCase(NetworkMessageDto msg, SharedPreferences prefs) {
        try {
            String senderEmail = msg.getFrom();
            // Парсим ID чата, назначенный создателем группы
            String[] parts = msg.getSubject().split(SPACE_STRING);
            long remoteChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            if (remoteChatId <= 0) {
                return 0;
            }

            String body = msg.getContent().trim();
            String[] bodyLines = body.split("\\r\\n");

            String incomingGroupName = bodyLines[0].trim();
            String creatorPublicKeyBase64 = bodyLines[1].trim();

            byte[] creatorPublicKeyBytes = Base64.decode(creatorPublicKeyBase64, NO_WRAP);

            // АВТО-СИНХРОНИЗАЦИЯ: Достаем служебный заголовок состава участников
            String membersHeader = Optional.ofNullable(msg.getHeaders().get("X-MailLis-Group-Members")).orElse(EMPTI_STRING);

            String[] incomingEmails = membersHeader.split(",");
            for (String email : incomingEmails) {
                // Метод addGroupMember внутри себя сам проверит дубликаты и привяжет к нужному localId!
                dbService.addGroupMember(remoteChatId, email.trim().toLowerCase());
            }

            // 1. Сохраняем публичный Tink-ключ создателя группы в таблицу контактов,
            // чтобы мы могли шифровать сообщения лично для него
            dbService.savePublicKey(senderEmail, creatorPublicKeyBytes);

            // 2. Создаем у себя заготовку группового чата (пока без секретного AES-ключа группы)
             if (dbService.saveReceivedGroupChat(remoteChatId, incomingGroupName, null, senderEmail)) {
                 emailClient.deleteMessage(msg.getId(), prefs);
                 return 1;
             }

            Log.i("MailLis_P2P_Group", "Поймали Pre-Invite в группу \"" + incomingGroupName + "\". Высылаем свой Tink-ключ в ответ...");

            // 3. АВТО-ОТВЕТ: Мгновенно отправляем создателю наш публичный Tink-ключ, чтобы он запечатал инвайт
            sendGroupPreInviteResponse(senderEmail, remoteChatId, prefs);

        } catch (Exception e) {
            Log.e("MailLis_P2P_Group", "Ошибка обработки Pre-Invite", e);
        }
        return 0;
    }

    private int groupPreInviteResponseCase(NetworkMessageDto msg, SharedPreferences prefs) {
        try {
            String[] parts = msg.getSubject().split(SPACE_STRING);
            long groupChatId = parts.length > 1 ? Long.parseLong(parts[1].trim()) : -1;

            if (groupChatId <= 0) {
                return 0;
            }
            String peerPublicKeyBase64 = msg.getContent().trim();
            byte[] peerPublicKeyBytes = Base64.decode(peerPublicKeyBase64, NO_WRAP);

            String senderEmail = msg.getFrom();
            // 1. Сохраняем полученный публичный Tink-ключ друга в контакты
            if (!dbService.savePublicKey(senderEmail, peerPublicKeyBytes)) {
                return 0;
            }
            emailClient.deleteMessage(msg.getId(), prefs);
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
            return 0;
        }
        return 1;
    }

    private int groupInviteCase(NetworkMessageDto msg, SharedPreferences prefs) throws GeneralSecurityException {
        // Извлекаем ID чата, который назначил создатель группы
        String[] subjectParts = msg.getSubject().split(SPACE_STRING);
        long remoteChatId = subjectParts.length > 1 ? Long.parseLong(subjectParts[1].trim()) : -1;

        if (remoteChatId <= 0) {
            return 0;
        }
        // Читаем тело письма: 1 строка - имя группы, 2 строка - Base64 зашифрованного ключа
        String[] bodyLines = msg.getContent().split("\\r\\n");

        String incomingGroupName = bodyLines[0].trim();
        String base64EncryptedKey = bodyLines[1].trim();

        byte[] encryptedKeyBytes = Base64.decode(base64EncryptedKey, NO_WRAP);

        // Расшифровываем СВОИМ локальным приватным Tink-ключом Keystore полученный AES-секрет группы!
        byte[] decryptedGroupKey = cryptoService.decryptBytes(encryptedKeyBytes);


        // Перезаписываем заготовку чата, добавляя туда рабочий расшифрованный BLOB секретного ключа!
        if (!dbService.saveReceivedGroupChat(remoteChatId, incomingGroupName, decryptedGroupKey, msg.getFrom())) {
            return 0;
        }

        emailClient.deleteMessage(msg.getId(), prefs);

        dbService.addGroupMember(remoteChatId, msg.getFrom()); // Фиксируем, что он в группе

        Log.i("MailLis_P2P_Group", "🎉 Успешно получили и расшифровали AES-ключ группы \"" + incomingGroupName + "\". Теперь мы полноценный участник!");

        return 1;
    }

    private int keyRequestCase(NetworkMessageDto msg, SharedPreferences prefs) {
        String senderEmail = msg.getFrom();
        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        // Если чата еще нет (или он был удален ранее) — создаем его заново!
        if (chatId < 0) {
            chatId = dbService.saveChat(senderEmail, senderEmail);
            Log.d("MailLis_P2P", "Получен запрос. Чат успешно создан у получателя. ID: " + chatId);
        }

        // СЛУЧАЙ 1: Нам прислали ключ и просят наш в ответ
        // Декодируем публичный ключ собеседника из Base64 обратно в byte[]
        String trim = msg.getContent().trim();
        byte[] peerPublicKeyBytes = Base64.decode(trim, NO_WRAP);

        //TODO Надо передавать название чата по сети

        // Сохраняем его в нашу базу SQLite (в таблицу chats для этого отправителя)
        if (!dbService.savePublicKey(senderEmail, peerPublicKeyBytes)) {
            return 0;
        }

        emailClient.deleteMessage(msg.getId(), prefs);

        // 3. ОТВЕЧАЕМ СВОИМ КЛЮЧОМ
        String serverUid = sendHandshakeResponse(senderEmail, chatId, prefs);

        emailClient.deleteMessage(serverUid, prefs);

        return 1;
    }

    private int keyResponseCase(NetworkMessageDto msg, SharedPreferences prefs) {
        String senderEmail = msg.getFrom();
        // 1. Проверяем, существует ли уже чат с этим отправителем в нашей локальной SQLite
        long chatId = dbService.getChatIdByRecipientEmail(senderEmail);
        if (chatId > 0) { // Обрабатываем, только если чат у нас существует
            // СЛУЧАЙ 2: Собеседник ответил на наш запрос и прислал свой ключ
            byte[] peerPublicKeyBytes = Base64.decode(msg.getContent(), NO_WRAP);
            // Сохраняем его в базу. Рукопожатие завершено!
            if (dbService.savePublicKey(senderEmail, peerPublicKeyBytes)) {
                emailClient.deleteMessage(msg.getId(), prefs);
                return 1;
            }

        }
        return 0;
    }

    public boolean sendEmailOverNetwork(String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, MailExecutorServiceImpl.OnMessagesSynchronizedListener listener) {

        if (listener == null) {
            Log.e("MailExecutorServiceImpl.sendEmailOverNetwork listener", "Критическая ошибка listener == null");
            return false;
        }
        // Handler нужен, чтобы вернуть результат в Главный (UI) поток Android
        var mainHandler = new Handler(Looper.getMainLooper());

        networkExecutor.execute(() -> {
            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
            try {
                // 💥 ЗАЩИТА: Мгновенно бронируем статус сообщения перед выходом в сеть по SMTP!
                dbService.markMessageAsSending(localMessageId);

                var messageDto = new NetworkMessageDto(messageText, recipientEmail, subject, null);
                String generatedServerUid = emailClient.sendMessage(messageDto, prefs);

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
    public boolean sendHandshakeRequest(String targetEmail, long chatId, SharedPreferences prefs) {
        try {
            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
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
                    targetEmail,
                    KEY_REQUEST,
                    myPublicKeyBase64,
                    localMessageId, // Передаем ID для последующего обновления server_uid и статуса ретрая
                    prefs,
                    new MailExecutorServiceImpl.OnMessagesSynchronizedListener() {
                        @Override
                        public void onSynchronized(MessageDto updatedMessageDto) {
                            // Сеть отработала успешно, сообщение улетело на Mail.ru
                            Log.d("MailLis_Handshake", "Запрос рукопожатия успешно отправлен в сеть на адрес: " + targetEmail);
                        }

                        @Override
                        public void onError(Exception e) {
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
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
        String myPublicKeyBase64;
        int localMessageId;

        try {
            myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();
        } catch (GeneralSecurityException | IOException e) {
            Log.e("MailLis_SendHandshakeResponse", "Ошибка ", e);
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

        String serverUid;
        try {
            var messageDto = new NetworkMessageDto(myPublicKeyBase64, targetEmail, KEY_RESPONSE, null);
            serverUid = emailClient.sendMessage(messageDto, prefs);

        } catch (AuthenticationFailedException e) {
            // Неверный пароль. Прерываем отправку всей пачки, так как данные авторизации сломаны.
            Log.e("MailLis_Retry", "Критическая ошибка: неверный пароль для " + myEmail);
            serverUid = EMPTI_STRING;

        } catch (MessagingException e) {
            // Любая другая ошибка (чаще всего — пропала сеть).
            // Оставляем сообщение со статусом 0 и выходим из цикла до следующего тика таймера.
            Log.w("MailLis_Retry", "Сбой отправки сообщения , попробуем позже. Причина: "
                    + e.getMessage()); //+ message.getId() + ", попробуем позже. Причина: " + e.getMessage());
            serverUid = EMPTI_STRING;
        } catch (Exception e) {
            Log.e("MailLis_Retry", "Сбой отправки сообщения Причина: " + e.getMessage());
            serverUid = EMPTI_STRING;
        }
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

                            sendGroupImageOverNetwork(chatId, rawImageBytes, msg.getId(), prefs, new OnMessagesSynchronizedListener() {
                                @Override
                                public void onSynchronized(MessageDto updatedMessages) {

                                }

                                @Override
                                public void onError(Exception e) {

                                }
                            });
                        } else {

                            // КАНАЛ РЕТРАЯ А: ГРУППОВОЕ СООБЩЕНИЕ (Веерный SMTP + AES-GCM)
                            byte[] groupSecretKey = dbService.getGroupSecretKey(chatId);

                            if (groupSecretKey != null && groupSecretKey.length > 0) {
                                // Заново шифруем сырой текст сообщения ключом группы
                                byte[] encryptedBytes = CryptoServiceImpl.encryptGroupText(msg.getText(), groupSecretKey);
                                String base64GroupText = Base64.encodeToString(encryptedBytes, NO_WRAP);

                                // Вызываем наш веерный SMTP метод
                                sendGroupMessageOverNetwork(chatId, msg.getSubject(), base64GroupText, msg.getId(), prefs, new OnMessagesSynchronizedListener() {
                                    @Override
                                    public void onSynchronized(MessageDto updatedMessages) {

                                    }

                                    @Override
                                    public void onError(Exception e) {

                                    }
                                });
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

                        sendImageOverNetwork(chatId, recipientEmail, rawImageBytes, msg.getId(), prefs, new MailExecutorServiceImpl.OnMessagesSynchronizedListener() {
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
                        sendEmailOverNetwork(recipientEmail, msg.getSubject(), sendTxt, msg.getId(), prefs,
                                new MailExecutorServiceImpl.OnMessagesSynchronizedListener() {
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

    public void sendImageOverNetwork(long chatId, String recipientEmail, byte[] rawImageBytes,
                                     final int localMessageId, SharedPreferences prefs, MailExecutorServiceImpl.OnMessagesSynchronizedListener listener) {
        networkExecutor.execute(() -> {
            try {
                dbService.markMessageAsSending(localMessageId); // Бронируем статус 3 (в процессе)

                byte[] peerPublicKeyBytes = dbService.getPublicKey(chatId);

                // Шифруем бинарный массив фотографии асимметричным ключом Tink
                byte[] encryptedImageBytes = cryptoService.encryptBytes(rawImageBytes, peerPublicKeyBytes);

                // Превращаем зашифрованный бинарник в безопасную для почты Base64-строку
                String base64EncryptedImage = Base64.encodeToString(encryptedImageBytes, NO_WRAP);

                var messageDto = new NetworkMessageDto(base64EncryptedImage, recipientEmail, CHAT_IMAGE_MAIL, null);
                String generatedServerUid = emailClient.sendMessage(messageDto, prefs);

                dbService.markMessageAsSent(localMessageId, generatedServerUid);
                dbService.updateServerUid(generatedServerUid, localMessageId);

                MessageDto freshMessages = dbService.getMessage(chatId);

                emailClient.deleteMessage(generatedServerUid, prefs);

                new Handler(Looper.getMainLooper()).post(() -> listener.onSynchronized(freshMessages));

            } catch (Exception e) {
                dbService.resetMessageStatusToUnsent(localMessageId);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e));
            }
        });
    }

    public void sendGroupImageOverNetwork(long chatId, byte[] rawImageBytes, int localMessageId, SharedPreferences prefs, MailExecutorServiceImpl.OnMessagesSynchronizedListener listener) {
        if (listener == null) {
            Log.e("MailExecutorServiceImpl.sendEmailOverNetwork listener", "Критическая ошибка listener == null");
            return;
        }
        // Handler нужен, чтобы вернуть результат в Главный (UI) поток Android
        var mainHandler = new Handler(Looper.getMainLooper());

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

                // Склеиваем список участников в одну строку через запятую для заголовка
                StringBuilder membersSb = new StringBuilder(myEmail); // Начинаем со своего email
                for (String email : recipients) {
                    membersSb.append(",").append(email);
                }
                String groupComposition = membersSb.toString();

                recipients = recipients
                        .stream()
                        .filter(e -> !e.equals(myEmail))
                        .collect(Collectors.toList());

                Map<String, String> headers = Map.of("X-MailLis-Group-Members", groupComposition);
                var messageDto = new NetworkMessageDto(base64EncryptedImage, EMPTI_STRING,
                        GROUP_IMAGE_MAIL + SPACE_STRING + chatId, headers);
                String generatedServerUid = emailClient.sendGroupMessage(recipients, messageDto, prefs);

                // Помечаем в локальной базе как отправлено (хотя бы сервером)
                dbService.markMessageAsSent(localMessageId, generatedServerUid);

                emailClient.deleteMessage(generatedServerUid, prefs);

                MessageDto freshlyLoadedMessages = dbService.getMessage(localMessageId);

                mainHandler.post(() -> listener.onSynchronized(freshlyLoadedMessages));
            } catch (Exception e) {
                Log.e("MailLis_SendGroupImage", "Ошибка ", e);
                dbService.resetMessageStatusToUnsent(localMessageId);
                mainHandler.post(() -> listener.onError(e));
            }
        });
    }

    public void sendGroupMessageOverNetwork(long chatId, String subject, String cipherTextBase64, int localMessageId, SharedPreferences prefs, MailExecutorServiceImpl.OnMessagesSynchronizedListener listener) {
        if (listener == null) {
            Log.e("MailExecutorServiceImpl.sendEmailOverNetwork listener", "Критическая ошибка listener == null");
            return;
        }
        // Handler нужен, чтобы вернуть результат в Главный (UI) поток Android
        var mainHandler = new Handler(Looper.getMainLooper());

        networkExecutor.execute(() -> {
            try {
                dbService.markMessageAsSending(localMessageId);
                String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

                // Получаем всех участников этой группы из SQLite
                List<String> recipients = dbService.getGroupMembers(chatId, myEmail);

                recipients = recipients
                        .stream()
                        .filter(e -> !e.equals(myEmail))
                        .collect(Collectors.toList());

                // Склеиваем список участников в одну строку через запятую для заголовка
                StringBuilder membersSb = new StringBuilder(myEmail); // Начинаем со своего email
                for (String email : recipients) {
                    membersSb.append(",").append(email);
                }
                String groupComposition = membersSb.toString();
                Map<String, String> headers = Map.of("X-MailLis-Group-Members", groupComposition);
                var messageDto = new NetworkMessageDto(cipherTextBase64, EMPTI_STRING, subject, headers);
                String generatedServerUid = emailClient.sendGroupMessage(recipients, messageDto, prefs);

                // Помечаем в локальной базе как отправлено (хотя бы сервером)
                dbService.markMessageAsSent(localMessageId, generatedServerUid);

                emailClient.deleteMessage(generatedServerUid, prefs);

                MessageDto freshlyLoadedMessages = dbService.getMessage(localMessageId);

                mainHandler.post(() -> listener.onSynchronized(freshlyLoadedMessages));
            } catch (Exception e) {
                Log.e("MailLis_GroupNet", "Не удалось доставить пакет", e);
                dbService.resetMessageStatusToUnsent(localMessageId);
                mainHandler.post(() -> listener.onError(e));
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

                String subject = EMPTI_STRING;
                String text = EMPTI_STRING;

                if (peerPublicKeyBytes != null && peerPublicKeyBytes.length > 5) {
                    // ВАРИАНТ А: Ключ друга есть! Шлем финальный ЗАШИФРОВАННЫЙ инвайт с ключом группы
                    byte[] encryptedKeyBytes = cryptoService.encryptBytes(groupSecretKey, peerPublicKeyBytes);
                    String base64EncryptedKey = Base64.encodeToString(encryptedKeyBytes, NO_WRAP);

                    subject = GROUP_INVITE_MAIL + SPACE_STRING + chatId;
                    text = groupName + "\r\n" + base64EncryptedKey;

                    Log.d("MailLis_Group", "Отправлен финальный защищенный инвайт для: " + recipientEmail);
                } else {
                    // ВАРИАНТ Б: Ключа друга нет! Шлем открытый Pre-Invite и прикрепляем СВОЙ публичный ключ
                    String myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();

                    subject = GROUP_PRE_INVITE_MAIL + SPACE_STRING + chatId;
                    text = groupName + "\r\n" + myPublicKeyBase64;

                    Log.w("MailLis_Group", "Ключа друга нет. Отправлен безопасный Pre-Invite для: " + recipientEmail);
                }
                Map<String, String> headers = Map.of("X-MailLis-Group-Members", groupComposition);
                var messageDto = new NetworkMessageDto(text, recipientEmail, subject, headers);
                String generatedServerUid = emailClient.sendMessage(messageDto, prefs);

                emailClient.deleteMessage(generatedServerUid, prefs);

            } catch (Exception e) {
                Log.e("MailLis_GroupInvite", "Ошибка ", e);
            }
        });
    }

    public void sendFileOverNetwork(String recipientEmail, String subject, long chatId, File fileToSend, boolean isGroup, SharedPreferences prefs) {
        networkExecutor.execute(() -> {
            try {
                if (fileToSend == null || !fileToSend.exists()) {
                    return;
                }

                // 1. Считываем бинарный файл в память смартфона
                byte[] fileBytes = new byte[(int) fileToSend.length()];
                try (FileInputStream fis = new FileInputStream(fileToSend)) {
                    fis.read(fileBytes);
                }

                // 2. Кодируем байты в безопасную Base64 строку для почтовых серверов [source]
                String base64File = Base64.encodeToString(fileBytes, DEFAULT);

                // 3. ШИФРОВАНИЕ TINK (Используем ваш отлаженный метод шифрования текста)
                // Мы упаковываем имя файла и его Base64-содержимое в один текстовый пакет через разделитель
                String plainPayload = fileToSend.getName() + "::SPLIT::" + base64File;


                String encryptedPayload;
                if (isGroup) {
                    byte[] groupSecretKey = dbService.getGroupSecretKey(chatId);
                    byte[] groupBytes = CryptoServiceImpl.encryptGroupText(plainPayload, groupSecretKey); // Ваше шифрование групп
                    encryptedPayload = Base64.encodeToString(groupBytes, NO_WRAP);
                } else {
                    byte[] publicKey = dbService.getPublicKey(chatId);
                    encryptedPayload = cryptoService.encrypt(plainPayload, publicKey); // Ваше приватное шифрование
                }

                var messageDto = new NetworkMessageDto(encryptedPayload, recipientEmail, subject, null);
                String generatedServerUid = emailClient.sendMessage(messageDto, prefs);
                Log.d("MailLis_Network_File", "Файл " + fileToSend.getName() + " успешно отправлен на " + recipientEmail);

                emailClient.deleteMessage(generatedServerUid, prefs);

            } catch (Exception e) {
                Log.e("MailLis_Network_File", "Критическая ошибка SMTP отправки файла", e);
            }
        });
    }

    public void sendGroupPreInviteResponse(String creatorEmail, long chatId, SharedPreferences prefs) {
        networkExecutor.execute(() -> {
            try {
                String myPublicKeyBas64 = cryptoService.getMyPublicKeyBas64();
                var messageDto = new NetworkMessageDto(myPublicKeyBas64, creatorEmail, GROUP_PRE_INVITE_RESPONSE_MAIL + SPACE_STRING + chatId, null);
                String generatedServerUid = emailClient.sendMessage(messageDto, prefs);

                emailClient.deleteMessage(generatedServerUid, prefs);

                Log.d("MailLis_Group", "Ответ на Pre-Invite успешно отправлен создателю: " + creatorEmail);
            } catch (Exception e) {
                Log.e("MailLis_GroupPreInvite", "Ошибка ", e);
            }
        });
    }

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
}
