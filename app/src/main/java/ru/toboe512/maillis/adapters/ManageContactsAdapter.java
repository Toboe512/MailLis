package ru.toboe512.maillis.adapters;


import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.dto.Contact;

public class ManageContactsAdapter extends RecyclerView.Adapter<ManageContactsAdapter.ContactViewHolder> {

    private final List<Contact> contacts;
    private final OnContactDeleteListener deleteListener;

    private final OnContactBanListener banListener;

    // ИНТЕРФЕЙС ДЛЯ БАНА
    public interface OnContactBanListener {
        void onContactBan(Contact contact, int position);
    }

    // Интерфейс, чтобы передать событие удаления обратно в Активити
    public interface OnContactDeleteListener {
        void onContactDelete(Contact contact, int position);
    }

    // Обновили конструктор, добавив banListener
    public ManageContactsAdapter(List<Contact> contacts, OnContactDeleteListener deleteListener, OnContactBanListener banListener) {
        this.contacts = contacts;
        this.deleteListener = deleteListener;
        this.banListener = banListener;
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact, parent, false);
        return new ContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
        Contact contact = contacts.get(position);
        if (contact != null) {
            holder.textViewEmail.setText(contact.getEmail());

            // Клик по кнопке "Удалить"
            holder.buttonDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onContactDelete(contact, position);
                }
            });

            // КЛИК ПО КНОПКЕ "ЗАБЛОКИРОВАТЬ"
            // ИСПРАВЛЕНИЕ: Добавляем жесткую проверку на null перед установкой клика бана!
            if (holder.buttonBan != null) {
                holder.buttonBan.setOnClickListener(v -> {
                    if (banListener != null) {
                        banListener.onContactBan(contact, position);
                    }
                });
            } else {
                Log.e("MailLis_Bug", "Критическая ошибка: кнопка buttonBan не найдена в item_contact.xml!");
            }
        }
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    static class ContactViewHolder extends RecyclerView.ViewHolder {
        TextView textViewEmail;
        ImageButton buttonDelete;

        ImageButton buttonBan; // Новое поле для кнопки бана

        public ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewEmail = itemView.findViewById(R.id.textViewContactEmail);
            buttonDelete = itemView.findViewById(R.id.imageButtonDeleteContact);
            buttonBan = itemView.findViewById(R.id.imageButtonBanContact); // Инициализация
        }
    }
}
