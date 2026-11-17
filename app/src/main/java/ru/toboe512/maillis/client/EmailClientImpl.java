package ru.toboe512.maillis.client;

import static jakarta.mail.Flags.Flag.DELETED;
import static jakarta.mail.Folder.READ_WRITE;
import static jakarta.mail.Message.RecipientType.TO;
import static ru.toboe512.maillis.consts.Consts.CHAT_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.GROUP_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_MESSAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.INBOX;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_PASSWORD;
import static ru.toboe512.maillis.consts.Consts.KEY_IMAP_PORT;
import static ru.toboe512.maillis.consts.Consts.KEY_IMAP_SERVER;
import static ru.toboe512.maillis.consts.Consts.KEY_SMTP_PORT;
import static ru.toboe512.maillis.consts.Consts.KEY_SMTP_SERVER;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_MESSAGE;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

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
import ru.toboe512.maillis.dto.NetworkMessageDto;

public class EmailClientImpl implements EmailClient {

    private static class Holder {
        private static EmailClient INSTANCE;
    }

    public static EmailClient getInstance() {
        if (Holder.INSTANCE == null) {
            Holder.INSTANCE = new EmailClientImpl();
        }
        return Holder.INSTANCE;
    }

    @Override
    public List<NetworkMessageDto> fetch(SharedPreferences prefs) throws MessagingException, IOException {
        List<NetworkMessageDto> result = new ArrayList<>();
        Store store = connectToImapServer(prefs);
        Folder inbox = store.getFolder(INBOX);
        inbox.open(READ_WRITE);

        int messageCount = inbox.getMessageCount();
        if (messageCount <= 0) {
            inbox.close(true);
            store.close();
            return result;
        }

        // Сканируем последние 25 писем, чтобы точно не пропустить ответы
        int startMessage = Math.max(1, messageCount - 25);
        Message[] messages = inbox.getMessages(startMessage, messageCount);

        for (Message msg : messages) {
            String subject = msg.getSubject();

            if (subject == null || !subject.contains(MAILLIS_MESSAGE)) {
                continue;
            }

            String from = ((InternetAddress) msg.getFrom()[0]).getAddress().trim();
            long timestamp = msg.getSentDate() != null ? msg.getSentDate().getTime() : System.currentTimeMillis();
            String[] mimeIds = msg.getHeader("Message-ID");
            String id = (mimeIds != null && mimeIds.length > 0) ? mimeIds[0] : null;
            String content = EMPTI_STRING;
            String[] headerArray = Optional.ofNullable(msg.getHeader("X-MailLis-Group-Members")).orElse(new String[]{});
            String header = headerArray.length > 0 ? headerArray[0] : EMPTI_STRING;
            Map<String, String> headers = Map.of("X-MailLis-Group-Members", header);

            if (subject.contains(GROUP_IMAGE_MAIL) || subject.contains(CHAT_IMAGE_MAIL)) {
                // Извлекаем Multipart контент из письма
                MimeMultipart multipart = (MimeMultipart) msg.getContent();
                MimeBodyPart part = (MimeBodyPart) multipart.getBodyPart(0);

                String rawBase64 = part.getContent().toString();
                // ОПТИМИЗАЦИЯ СЕТЕВОГО ТЕКСТА: Жестко вырезаем все скрытые переносы строк (\r, \n)
                // и пробелы, которые Mail.ru вставил во вложение для транспортировки!
                content = rawBase64
                        .replaceAll("\\r", EMPTI_STRING)
                        .replaceAll("\\n", EMPTI_STRING)
                        .trim();
            } else {
                content = msg.getContent().toString().trim();
            }
            result.add(new NetworkMessageDto(id, content, from, subject, timestamp, headers));
        }

        // Закрываем с флагом true для применения удаления (Expunge)
        inbox.close(true);
        store.close();

        return result;
    }

    public String sendMessage(NetworkMessageDto messageDto, SharedPreferences prefs) throws MessagingException {
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);

        // Получаем готовую SMTP сессию
        Session session = createSmtpSession(prefs);
        var message = new MimeMessage(session);

        String subject = messageDto.getSubject();

        if (subject.contains(CHAT_IMAGE_MAIL)) {
            var multipart = getMimeMultipart(System.currentTimeMillis(), messageDto.getContent());
            message.setContent(multipart);
        } else {
            message.setText(messageDto.getContent());
        }

        message.setFrom(new InternetAddress(myEmail));
        message.setRecipients(TO, InternetAddress.parse(messageDto.getTo()));
        message.setSubject(subject);
        if ((messageDto.getHeaders() != null) && (!messageDto.getHeaders().isEmpty())) {
            for (Map.Entry<String, String> header : messageDto.getHeaders().entrySet()) {
                message.addHeader(header.getKey(), header.getValue()); //"X-MailLis-Group-Members"
            }
        }
        // 1. ПРИНУДИТЕЛЬНО ПРИМЕНЯЕМ ИЗМЕНЕНИЯ, чтобы сгенерировался Message-ID
        message.saveChanges();
        // 2. Отправляем в сеть
        Transport.send(message);
        // 3. БЕЗОПАСНО ИЗВЛЕКАЕМ Message-ID
        return message.getMessageID(); // Используем встроенный метод вместо getHeader();
    }

    public void deleteMessage(final String serverUid, SharedPreferences prefs) {
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
                    // принудительно ставим флаг удаления через папку
                    inbox.setFlags(foundMessages, new Flags(DELETED), true);
                    // Принудительно заставляем IMAP-сервер физически стереть помеченные сообщения прямо сейчас
                    inbox.expunge();
                    Log.d("MailLis_Delete", "Письмо с Message-ID успешно найдено и удалено.");
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

    public String sendGroupMessage(List<String> recipients, NetworkMessageDto messageDto, SharedPreferences prefs) throws MessagingException {
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING);
        Session session = createSmtpSession(prefs);
        String generatedServerUid = EMPTI_STRING;

        // ВЕЕРНАЯ РАССЫЛКА: Шлем копию письма каждому участнику отдельно
        for (String recipientEmail : recipients) {
            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(myEmail));
            message.setRecipients(TO, InternetAddress.parse(recipientEmail));

            // Передаем ID чата в теме письма, чтобы получатели знали, в какую именно группу положить сообщение!
            message.setSubject(messageDto.getSubject());

            if (messageDto.getSubject().contains(GROUP_MESSAGE_MAIL)) {
                message.setText(messageDto.getContent());
            }
            if (messageDto.getSubject().contains(GROUP_IMAGE_MAIL)) {
                var multipart = getMimeMultipart(System.currentTimeMillis(), messageDto.getContent());
                message.setContent(multipart);
            }

            if ((messageDto.getHeaders() != null) && (!messageDto.getHeaders().isEmpty())) {
                for (Map.Entry<String, String> header : messageDto.getHeaders().entrySet()) {
                    message.addHeader(header.getKey(), header.getValue()); //"X-MailLis-Group-Members"
                }
            }

            message.saveChanges();

            Transport.send(message);

            generatedServerUid = message.getMessageID();
        }

        return generatedServerUid;
    }

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
}
