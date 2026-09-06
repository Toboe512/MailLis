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
import ru.toboe512.maillis.dto.Contact;

public class CreateChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private ContactsAdapter adapter;
    private List<Contact> contactList;
    private ChatDbHelper dbHelper;
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


        dbHelper = new ChatDbHelper(this);
        contactList = new ArrayList<>();

        // Настраиваем список RecyclerView
        editTextGroupName = findViewById(R.id.editTextGroupName);
        recyclerView = findViewById(R.id.recyclerViewSelectContacts);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Загружаем данные из SQLite
        loadContactsFromDatabase();

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

                // Сохраняем созданный чат в базу данных SQLite
                saveChatToDatabase(groupName, selectedEmails);
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

    // Метод нативного чтения строк из таблицы SQLite
    private void loadContactsFromDatabase() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрос всех колонок из таблицы contacts
        Cursor cursor = db.query("contacts", null, null, null, null, null, "name ASC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                // Получаем индексы колонок по их именам
                int idIndex = cursor.getColumnIndex("id");
                int nameIndex = cursor.getColumnIndex("name");
                int emailIndex = cursor.getColumnIndex("email");

                // Считываем значения типов данных
                if (idIndex >= 0 && nameIndex >= 0 && emailIndex >= 0) {
                    int id = cursor.getInt(idIndex);
                    String name = cursor.getString(nameIndex);
                    String email = cursor.getString(emailIndex);

                    // Добавляем объект в наш массив списка
                    contactList.add(new Contact(id, name, email));
                }
            }
            cursor.close(); // Обязательно закрываем курсор
        }

        // КРИТИЧЕСКИ ВАЖНО: Принудительно заставляем список на экране перерисовать полученные строки
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        // Диагностика: если база пуста, вежливо сообщаем об этом
        if (contactList.isEmpty()) {
            Toast.makeText(this, "Список контактов пока пуст. Добавьте кого-нибудь через '+'.", Toast.LENGTH_LONG).show();
        }
        db.close();
    }
    // Логика записи нового чата в SQLite
    private void saveChatToDatabase(String title, List<String> participants) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();

        // Берем email первого выбранного контакта
        String targetEmail = participants.get(0);

        values.put("title", title); // Красивое человеческое название (например, "Чат с Алексеем")
        values.put("type", "PERSONAL");
        values.put("recipient_email", targetEmail); // Сохраняем email в отдельную колонку!
        values.put("last_message", "Чат создан");
        values.put("timestamp", System.currentTimeMillis());

        long newChatId = db.insert("chats", null, values);
        db.close();

        if (newChatId != -1) {
            Toast.makeText(this, "Чат успешно создан!", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Ошибка создания чата", Toast.LENGTH_SHORT).show();
        }
    }
}
