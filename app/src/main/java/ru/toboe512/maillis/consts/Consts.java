package ru.toboe512.maillis.consts;

public class Consts {

    // Заголовки сообщений
    public static final String KEY_REQUEST = "[MailLis-Key-Request]";
    public static final String KEY_RESPONSE = "[MailLis-Key-Response]";
    public static final String CHAT_MESSAGE = "[MailLis-Chat-Message]";
    public static final String READ_RECEIPT = "[MailLis-Read-Receipt]";

    public static final String CHAT_IMAGE_MAIL = "[MailLis-Image-Message]";

    public static final String GROUP_MESSAGE_MAIL = "[MailLis-Group-Message]";

    public static final String GROUP_IMAGE_MAIL = "[MailLis-Group-Image]";

    public static final String GROUP_INVITE_MAIL = "[MailLis-Group-Invite]";
    public static final String GROUP_PRE_INVITE_MAIL = "[MailLis-Group-PreInvite]";
    public static final String GROUP_PRE_INVITE_RESPONSE_MAIL = "[MailLis-Group-PreInvite-Response]";
    //public static final String READ_RECEIPT = "[MAILLIS-READ-RECEIPT]";

    // Имя файла настроек на диске телефона
    public static final String PREFS_NAME = "MailLis_Prefs";

    // Ключи для сохранения полей настроек
    public static final String KEY_IMAP_SERVER = "imap_server";
    public static final String KEY_IMAP_PORT = "imap_port";
    public static final String KEY_SMTP_SERVER = "smtp_server";
    public static final String KEY_SMTP_PORT = "smtp_port";
    public static final String KEY_EMAIL_LOGIN = "email_login";
    public static final String KEY_EMAIL_PASSWORD = "email_password";

    // Типы сообщений
    public static final String TEXT_MSG = "TEXT";
    public static final String IMAGE_MSG = "IMAGE";

    //Метка входящих сообщений
    public static final String INBOX = "INBOX";

    // Интервал опроса сервера в миллисекундах
    public static final int UPDATE_INTERVAL_MS = 5000;

    // Разное
    public static final String EMPTI_STRING = "";
    public static final String SPACE_STRING = " ";
}
