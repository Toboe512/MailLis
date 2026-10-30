package ru.toboe512.maillis.adapters;


import static android.content.Context.MODE_PRIVATE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.RelativeLayout.ALIGN_PARENT_END;
import static android.widget.RelativeLayout.ALIGN_PARENT_LEFT;
import static android.widget.RelativeLayout.ALIGN_PARENT_RIGHT;
import static android.widget.RelativeLayout.ALIGN_PARENT_START;
import static android.widget.RelativeLayout.TRUE;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;
import static ru.toboe512.maillis.consts.Consts.IMAGE_MSG;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.MessageDto;
import ru.toboe512.maillis.utils.MessageDiffCallback;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    // 1. ИНТЕРФЕЙС СЛУШАТЕЛЯ (ОБЯЗАТЕЛЬНО должен быть объявлен СТРОГО здесь, внутри класса)
    public interface MultiChoiceListener {
        void onMultiChoiceStateChanged(boolean isActive, int selectedCount);
    }

    private final List<MessageDto> messageDtoList = new ArrayList<>();
    private final Context context;
    private final DBService dbService;
    private final EmailClient emailClient;

    // Поля на уровне класса вашего MessagesAdapter:
    private boolean isMultiChoiceMode = false;
    private final List<MessageDto> selectedMessages = new ArrayList<>();

    // Используем Set<Integer>, он на уровне языка гарантирует 100% уникальность ID
    private final Set<Integer> selectedMessageIds = new HashSet<>();
    private MultiChoiceListener multiChoiceListener;

    public MessagesAdapter(Context context, EmailClient emailClient, DBService dbService) {
        this.context = context;
        this.emailClient = emailClient;
        this.dbService = dbService;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    public void setMultiChoiceListener(MultiChoiceListener listener) {
        this.multiChoiceListener = listener;
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        MessageDto messageDto = messageDtoList.get(position);

        // =========================================================================
        // 1. ОПТИМИЗАЦИЯ КОНТЕНТА: Читаем файл с диска ТОЛЬКО если текст/картинка изменились!
        // Защита от ежесекундного чтения накопителя и фризов UI.
        // =========================================================================
        if (IMAGE_MSG.equals(messageDto.getMessageType()) && messageDto.getFilePath() != null) {
            holder.textViewText.setVisibility(GONE);
            if (holder.imageViewPhoto != null) {
                holder.imageViewPhoto.setVisibility(VISIBLE);

                // Проверяем: если картинка уже была загружена в этот ImageView, не декодируем её заново!
                if (holder.imageViewPhoto.getDrawable() == null) {
                    new Thread(() -> {
                        // Декодируем тяжелый Bitmap в фоне
                        Bitmap bitmap = BitmapFactory.decodeFile(messageDto.getFilePath());
                        if (bitmap != null) {
                            holder.itemView.post(() -> holder.imageViewPhoto.setImageBitmap(bitmap));
                        } else {
                            holder.itemView.post(() ->
                                    holder.imageViewPhoto.setImageResource(android.R.drawable.ic_menu_report_image));
                        }
                    }).start();
                }
            }
        } else {
            if (holder.imageViewPhoto != null) {
                holder.imageViewPhoto.setVisibility(GONE);
                holder.imageViewPhoto.setImageDrawable(null); // Сбрасываем старый Bitmap кэша
            }
            holder.textViewText.setVisibility(VISIBLE);

            // Оптимизация: меняем текст только если он реально отличается от текущего
            if (!messageDto.getText().equals(holder.textViewText.getText().toString())) {
                holder.textViewText.setText(messageDto.getText());
            }
        }

        // 2. УПРАВЛЕНИЕ EMAIL ОТПРАВИТЕЛЯ (ИСПОЛЬЗУЕМ КОРРЕКТНЫЙ ФЛАГ ТИПА КОМНАТЫ ИЗ АДАПТЕРА)
        if (holder.textViewSender != null) {
            if (messageDto.isGroup() && !messageDto.isMine()) {
                holder.textViewSender.setVisibility(VISIBLE);
                if (!messageDto.getSenderEmail().equals(holder.textViewSender.getText().toString())) {
                    holder.textViewSender.setText(messageDto.getSenderEmail());
                }
            } else {
                holder.textViewSender.setVisibility(View.INVISIBLE); // INVISIBLE сохраняет связи layout_below
            }
        }

        // 3. ДИНАМИЧЕСКОЕ ВЫРАВНИВАНИЕ СТОРОН (ПРАВО / ЛЕВО)
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) holder.cardMessage.getLayoutParams();

        if (messageDto.isMine()) {
            params.removeRule(ALIGN_PARENT_LEFT);
            params.removeRule(ALIGN_PARENT_START);
            params.addRule(ALIGN_PARENT_RIGHT, TRUE);
            params.addRule(ALIGN_PARENT_END, TRUE);

            // 4. МГНОВЕННОЕ ОБНОВЛЕНИЕ ГАЛОЧЕК (СВЯЗКА С НАШИМ СЕКУНДНЫМ ТАЙМЕРОМ)
            if (holder.textViewStatus != null) {
                holder.textViewStatus.setVisibility(VISIBLE);

                String currentIcon = holder.textViewStatus.getText().toString();
                String targetIcon = "✓";

                int status = messageDto.getStatus();
                switch (status) {
                    case 0:
                        targetIcon = "🕒";
                        break; // Ожидает сеть
                    case 3:
                        targetIcon = "⏳";
                        break; // Отправка SMTP в процессе
                    case 1:
                        targetIcon = "✓";
                        break;  // Доставлено на Mail.ru
                    case 2:
                        targetIcon = "✓✓";
                        break; // Прочитано
                }

                // Меням иконку ТОЛЬКО если статус в SQLite реально изменился!
                // Это полностью ликвидирует ежесекундное мерцание текста на экране
                if (!currentIcon.equals(targetIcon)) {
                    holder.textViewStatus.setText(targetIcon);
                }
            }
        } else {
            params.removeRule(ALIGN_PARENT_RIGHT);
            params.removeRule(ALIGN_PARENT_END);
            params.addRule(ALIGN_PARENT_LEFT, TRUE);
            params.addRule(ALIGN_PARENT_START, TRUE);

            if (holder.textViewStatus != null) {
                holder.textViewStatus.setVisibility(GONE);
            }
        }

        holder.cardMessage.setLayoutParams(params);

//        // ОБРАБОТКА ДОЛГОГО КЛИКА НА СООБЩЕНИЕ
//        holder.itemView.setOnLongClickListener(v -> {
//            showDeleteMessageDialog(messageDto, position);
//            return true;
//        });

        // =========================================================================
        // ИСПРАВЛЕНИЕ 1: Проверяем выделение СТРОГО по наличию ID сообщения в нашем Set!
        // Теперь даже если таймер обновит объекты, ячейка сохранит визуальное выделение.
        // =========================================================================
        if (selectedMessageIds.contains(messageDto.getId())) {
            holder.itemView.setBackgroundColor(android.graphics.Color.parseColor("#220088CC")); // Синяя подсветка
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        // Обработка обычного короткого клика
        holder.itemView.setOnClickListener(v -> {
            if (isMultiChoiceMode) {
                toggleMessageSelectionById(messageDto.getId(), position);
            }
        });

        // Обработка долгого клика
        holder.itemView.setOnLongClickListener(v -> {
            if (!isMultiChoiceMode) {
                isMultiChoiceMode = true;
                toggleMessageSelectionById(messageDto.getId(), position);
                return true;
            }
            return false;
        });
    }

    /**
     * Переключает состояние выделения сообщения на основе его уникального числового ID в SQLite.
     */
    private void toggleMessageSelectionById(int messageId, int position) {
        // Если ID уже есть в списке — значит пользователь кликнул второй раз, снимаем выделение!
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId);
        } else {
            // Если ID нет — добавляем в список удаления
            selectedMessageIds.add(messageId);
        }

        // Мягко перерисовываем только кликнутую ячейку
        notifyItemChanged(position);

        // Если сняли выделение со всех элементов — полностью тушим режим мультивыбора
        if (selectedMessageIds.isEmpty()) {
            isMultiChoiceMode = false;
        }

        // Передаем точный и честный счетчик уникальных ID в ChatActivity
        if (multiChoiceListener != null) {
            multiChoiceListener.onMultiChoiceStateChanged(isMultiChoiceMode, selectedMessageIds.size());
        }
    }

    /**
     * Возвращает список всех выделенных объектов MessageDto для метода удаления ChatActivity.
     */
    public List<MessageDto> getSelectedMessages() {
        List<MessageDto> selectedList = new ArrayList<>();
        for (MessageDto msg : messageDtoList) {
            // Если ID этого сообщения сохранен в нашем буфере — добавляем его в пачку на удаление
            if (selectedMessageIds.contains(msg.getId())) {
                selectedList.add(msg);
            }
        }
        return selectedList;
    }

    /**
     * Полностью сбрасывает режим множественного выбора при нажатии "Отмена" или "Назад".
     */
    public void clearSelection() {
        isMultiChoiceMode = false;
        selectedMessageIds.clear(); // Полностью зачищаем числовой буфер
        notifyDataSetChanged();     // Перерисовываем экран, снимая синюю подсветку
        if (multiChoiceListener != null) {
            multiChoiceListener.onMultiChoiceStateChanged(false, 0);
        }
    }

    @Override
    public int getItemCount() {
        return messageDtoList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        CardView cardMessage;
        LinearLayout layoutBackground;
        TextView textViewSender;
        TextView textViewText;
        TextView textViewStatus;
        ImageView imageViewPhoto;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            cardMessage = itemView.findViewById(R.id.cardMessage);
            layoutBackground = itemView.findViewById(R.id.layoutMessageBackground);
            textViewSender = itemView.findViewById(R.id.textViewSender);
            textViewText = itemView.findViewById(R.id.textViewMessageText);
            textViewStatus = itemView.findViewById(R.id.textViewMessageStatus);
            imageViewPhoto = itemView.findViewById(R.id.imageViewMessagePhoto);
        }
    }

    // Всплывающее окно подтверждения удаления
    private void showDeleteMessageDialog(MessageDto messageDto, int position) {
        var builder = new AlertDialog.Builder(context);
        builder.setTitle("Удалить сообщение");
        builder.setMessage("Вы уверены, что хотите удалить это сообщение?");

        builder.setPositiveButton("Удалить", (dialog, which) -> {
            deleteMessageFromDb(messageDto.getId(), position);
            // ЗАПУСКАЕМ СЕТЕВОЕ УДАЛЕНИЕ С СЕРВЕРА MAIL.RU
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            emailClient.deleteMessageFromServer(messageDto.getServerUid(), prefs);
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Удаление строки из SQLite
    private void deleteMessageFromDb(int messageId, int position) {

        // Удаляем строго по первичному ключу ID сообщения
        int rowsDeleted = dbService.deleteMessage(messageId);

        messageDtoList.remove(position);
        // Красивая плавная анимация исчезновения сообщения из списка
        notifyItemRemoved(position);
        if (rowsDeleted <= 0) {
            return;
        }
        notifyItemRangeChanged(position, messageDtoList.size());
        Toast.makeText(context, "Сообщение удалено", Toast.LENGTH_SHORT).show();
    }

    // Внутри класса MessagesAdapter:
    public void setMessages(List<MessageDto> newMessageDtos) {
        // Запускаем автоматический расчет разницы между старым и новым списками сообщений
        MessageDiffCallback diffCallback = new MessageDiffCallback(messageDtoList, newMessageDtos);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

        // Обновляем ссылку на массив данных
        messageDtoList.clear();
        messageDtoList.addAll(newMessageDtos);

        // Приказываем DiffUtil плавно и без мерцания обновить только изменившиеся галочки!
        diffResult.dispatchUpdatesTo(this);
    }
}
