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
}
