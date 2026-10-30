package ru.toboe512.maillis.config;


/**
 * Глобальная конфигурация
 * флагов возможностей
 * мессенджера MailLis.
 *
 */

public class Config {
    // FEATURE TOGGLE: Групповые P2P чаты.
    // false — скрыто для релиза, true — включено для разработчика при отладке
    public static final boolean IS_GROUP_CHATS_ENABLED = true;

    // Имя файла настроек на диске телефона
    public static final String PREFS_NAME = "MailLis_Prefs";
    public static final String KEY_EMAIL_LOGIN = "email_login";

    // Ключ для хранения интервала синхронизации (в миллисекундах)
    public static final String KEY_SYNC_INTERVAL = "sync_interval_ms";

    // Дефолтный интервал опроса (20 секунд), если пользователь ещё ничего не менял
    public static final long DEFAULT_SYNC_INTERVAL_MS = 20000;
}
