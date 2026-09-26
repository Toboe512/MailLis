package ru.toboe512.maillis.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ContactsAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.Contact;

public class CreateChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private ContactsAdapter adapter;
    private List<Contact> contactList;
    private ChatDbHelper dbHelper;

    private DBService dbService;
    private EditText editTextGroupName;

    private EmailClient emailClient;
    private CryptoService cryptoService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_chat);

        // Настраиваем Toolbar и кнопку Назад
        Toolbar toolbar = findViewById(R.id.toolbarCreateChat);

        // Автоматический сдвиг под строку состояния (часы и батарею)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), v.getPaddingBottom());
            v.getLayoutParams().height = (int) (insets.top + (56 * getResources().getDisplayMetrics().density));
            return windowInsets;
        });

        // >>> ВОТ ЭТОТ КОД НУЖНО ДОБАВИТЬ: Прямой клик по стрелке на самом тулбаре <<<
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(); // Мгновенно закрывает экран и возвращает на MainActivity
            }
        });
        dbHelper = new ChatDbHelper(this);
        dbService = new DBServiceSQLite(dbHelper);
        contactList = new ArrayList<>();

        // Стрелочка назад
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("Чат");
        }

        // Настраиваем список RecyclerView
        editTextGroupName = findViewById(R.id.editTextGroupName);
        recyclerView = findViewById(R.id.recyclerViewSelectContacts);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Загружаем данные из SQLite
        contactList = dbService.getContacts();

        // КРИТИЧЕСКИ ВАЖНО: Принудительно заставляем список на экране перерисовать полученные строки
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        // Диагностика: если база пуста, вежливо сообщаем об этом
        if (contactList.isEmpty()) {
            Toast.makeText(this, "Список контактов пока пуст. Добавьте кого-нибудь через '+'.", Toast.LENGTH_LONG).show();
        }

        adapter = new ContactsAdapter(this, contactList);
        recyclerView.setAdapter(adapter);

        dbHelper = adapter.getDbHelper();

        cryptoService = new CryptoServiceImpl(CreateChatActivity.this);

        emailClient = new EmailClientImpl(dbHelper, dbService, cryptoService);


        Button buttonCreateContact = findViewById(R.id.buttonViewContactTitle);
        buttonCreateContact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(CreateChatActivity.this, AddContactActivity.class);
                startActivity(intent);
                finish();
            }
        });

        Button buttonCreateGroup = findViewById(R.id.buttonCreateGroup);
        buttonCreateGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String groupName = editTextGroupName.getText().toString().trim();

                // Проверяем, ввел ли пользователь имя чата
                if (groupName.isEmpty()) {
                    Toast.makeText(CreateChatActivity.this, "Введите название группы!", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Считаем, сколько человек выбрано галочками
                List<String> selectedEmails = new ArrayList<>();
                for (Contact c : contactList) {
                    if (c.isSelected()) {
                        selectedEmails.add(c.getEmail());
                    }
                }

                if (selectedEmails.isEmpty()) {
                    Toast.makeText(CreateChatActivity.this, "Выберите хотя бы одного участника!", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Загружаем наш собственный логин из SharedPreferences
                SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);
                //TODO добавить возможность групповых чатов

                String recipientEmail = selectedEmails.get(0);

                //если чат ещё не в БД
                //  if (dbService.getChatIdBySender(selectedEmails.get(0)) <= 0) {
                long newChatId = dbService.saveChat(groupName, recipientEmail);// Сохраняем созданный чат в базу данных SQLite
                try {
                    String myPublicKeyBas64 = cryptoService.getMyPublicKeyBas64();
                    byte[] publicKeyBytes = emailClient.sendHandshakeRequest(recipientEmail, CreateChatActivity.this, newChatId, prefs, myPublicKeyBas64);
                    //dbService.saveSharedSecretKey(recipientEmail, publicKeyBytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (GeneralSecurityException e) {
                    throw new RuntimeException(e);
                }

                //}


//                if (newChatId != -1) {
//                    Toast.makeText(this, "Чат успешно создан!", Toast.LENGTH_SHORT).show();
//                    finish();
//                } else {
//                    Toast.makeText(this, "Ошибка создания чата", Toast.LENGTH_SHORT).show();
//                }

                finish();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
