package ru.toboe512.maillis.activity;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;

public class AddContactActivity extends AppCompatActivity {

    private EditText editTextName;
    private EditText editTextEmail;
    private DBService dbService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_contact);

        // Включаем встроенную кнопку "Назад" в ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Новый контакт");
        }

        // Инициализируем БД
        dbService = DBServiceSQLite.getInstance(this);

        editTextName = findViewById(R.id.editTextContactName);
        editTextEmail = findViewById(R.id.editTextContactEmail);

        Button buttonSave = findViewById(R.id.buttonSaveContact);

        buttonSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = editTextName.getText().toString().trim();
                String email = editTextEmail.getText().toString().trim();

                if (name.isEmpty() || email.isEmpty()) {
                    Toast.makeText(AddContactActivity.this, "Заполните все поля!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(AddContactActivity.this, name + " добавлен в контакты", Toast.LENGTH_SHORT).show();

                    long newRowId = dbService.saveContact(name, email);

                    if (newRowId == -1) {
                        Toast.makeText(AddContactActivity.this, "Ошибка: Контакт с таким Email уже есть!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(AddContactActivity.this, name + " успешно добавлен в базу данных!", Toast.LENGTH_SHORT).show();
                        finish(); // Закрываем экран и возвращаемся назад
                    }
                }
            }
        });
    }

    // Обработка нажатия на стрелочку "Назад" в ActionBar
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}