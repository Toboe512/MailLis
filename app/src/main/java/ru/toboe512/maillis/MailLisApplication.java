package ru.toboe512.maillis;

import android.app.Application;

import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;

//@HiltAndroidApp // Обязательная аннотация!
public class MailLisApplication extends Application {

    private DBService dbService;
    private EmailClient emailClient;
    @Override
    public void onCreate() {
        super.onCreate();

        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        if (emailClient.getCryptoService() != null) {
            return;
        }
    }
}
