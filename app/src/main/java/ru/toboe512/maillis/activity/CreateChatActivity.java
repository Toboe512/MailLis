package ru.toboe512.maillis.activity;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ContactsAdapter;
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

        dbService = new DBServiceSQLite(CreateChatActivity.this) ;
        contactList = new ArrayList<>();

        // Настраиваем список RecyclerView
        editTextGroupName = findViewById(R.id.editTextGroupName);
        recyclerView = findViewById(R.id.recyclerViewSelectContacts);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Загружаем данные из SQLite
        contactList = dbService.loadContactsFromDatabase();

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
//TODO добавить возможность групповых чатов
                // Сохраняем созданный чат в базу данных SQLite
                long newChatId = dbService.saveChatToDatabase(groupName, selectedEmails.get(0));

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
