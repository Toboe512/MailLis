package ru.toboe512.maillis.adapters;


import static android.content.Context.MODE_PRIVATE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.RelativeLayout.ALIGN_PARENT_END;
import static android.widget.RelativeLayout.ALIGN_PARENT_LEFT;
import static android.widget.RelativeLayout.ALIGN_PARENT_RIGHT;
import static android.widget.RelativeLayout.ALIGN_PARENT_START;
import static android.widget.RelativeLayout.TRUE;

import static ru.toboe512.maillis.consts.Consts.IMAGE_MSG;
import static ru.toboe512.maillis.consts.Consts.PREFS_NAME;

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

import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.MessageDto;
import ru.toboe512.maillis.utils.MessageDiffCallback;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<MessageDto> messageDtoList;
    private final Context context;
    private final DBService dbService;
    private final EmailClient emailClient;

    public MessagesAdapter(Context context, List<MessageDto> messageDtoList, EmailClient emailClient, DBService dbService) {
        this.context = context;
        this.messageDtoList = messageDtoList;
        this.emailClient = emailClient;
        this.dbService = dbService;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        MessageDto messageDto = messageDtoList.get(position);

        // Считываем LayoutParams. Убедитесь, что контейнер holder.cardMessage в XML — это прямой дочерний элемент RelativeLayout!
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) holder.cardMessage.getLayoutParams();

        // 1. НАСТРОЙКА ОТОБРАЖЕНИЯ КОНТЕНТА (ТЕКСТ ИЛИ ФОТО)
        if (IMAGE_MSG.equals(messageDto.getMessageType()) && messageDto.getFilePath() != null) {
            holder.textViewText.setVisibility(GONE);
            if (holder.imageViewPhoto != null) {
                holder.imageViewPhoto.setVisibility(VISIBLE);
                Bitmap bitmap = BitmapFactory.decodeFile(messageDto.getFilePath());
                if (bitmap != null) {
                    holder.imageViewPhoto.setImageBitmap(bitmap);
                } else {
                    holder.imageViewPhoto.setImageResource(android.R.drawable.ic_menu_report_image);
                }
            }
        } else {
            if (holder.imageViewPhoto != null) {
                holder.imageViewPhoto.setVisibility(GONE);
            }
            holder.textViewText.setVisibility(VISIBLE);
            holder.textViewText.setText(messageDto.getText());
        }

        // 2. ИСПРАВЛЕНИЕ: ДИНАМИЧЕСКОЕ ВЫРАВНИВАНИЕ СТОРОН (ПРАВО / ЛЕВО)
        if (messageDto.isMine()) {
            // --- НАШИ ИСХОДЯЩИЕ СООБЩЕНИЯ (ПРИЖИМАЕМ ВПРАВО) ---

            // Сначала полностью очищаем старые правила, оставшиеся от переиспользования ячеек
            params.removeRule(ALIGN_PARENT_LEFT);
            params.removeRule(ALIGN_PARENT_START);

            // Добавляем правило прижатия к правому краю экрана
            params.addRule(ALIGN_PARENT_RIGHT, TRUE);
            params.addRule(ALIGN_PARENT_END, TRUE);

            holder.textViewSender.setText(messageDto.getSenderEmail());

            // Настройка галочек статуса
            if (holder.textViewStatus != null) {
                holder.textViewStatus.setVisibility(VISIBLE);
                int status = messageDto.getStatus();
                switch (status) {
                    case 0:
                        holder.textViewStatus.setText("🕒");
                        break; // Ожидание сети
                    case 3:
                        holder.textViewStatus.setText("⏳");
                        break; // В процессе отправки SMTP
                    case 1:
                        holder.textViewStatus.setText("✓");
                        break;  // На сервере Mail.ru
                    case 2:
                        holder.textViewStatus.setText("✓✓");
                        break; // Прочитано другом
                    default:
                        holder.textViewStatus.setText("✓");
                        break;
                }
            }
        } else {
            // --- ЧУЖИЕ ВХОДЯЩИЕ СООБЩЕНИЯ (ПРИЖИМАЕМ ВЛЕВО) ---
            // Очищаем правила прижатия к правому краю
            params.removeRule(ALIGN_PARENT_RIGHT);
            params.removeRule(ALIGN_PARENT_END);

            // Добавляем правило прижатия к левому краю экрана
            params.addRule(ALIGN_PARENT_LEFT, TRUE);
            params.addRule(ALIGN_PARENT_START, TRUE);

            // Прячем галочки статуса для чужих сообщений
            if (holder.textViewStatus != null) {
                holder.textViewStatus.setVisibility(GONE);
            }
            holder.textViewSender.setVisibility(GONE);
        }

        // КРИТИЧЕСКИ ВАЖНО: Применяем обновленные правила выравнивания обратно к контейнеру ячейки!
        holder.cardMessage.setLayoutParams(params);

        // ОБРАБОТКА ДОЛГОГО КЛИКА НА СООБЩЕНИЕ
        holder.itemView.setOnLongClickListener(v -> {
            showDeleteMessageDialog(messageDto, position);
            return true;
        });
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
            emailClient.deleteMessageFromMailRuServer(messageDto.getServerUid(), prefs);
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
        // Вычисляем разницу между старым списком (this.messageList) и новым
        var diffCallback = new MessageDiffCallback(this.messageDtoList, newMessageDtos);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

        // Обновляем данные в памяти адаптера
        this.messageDtoList.clear();
        this.messageDtoList.addAll(newMessageDtos);

        // Применяем точечные анимации (notifyItemInserted, notifyItemChanged автоматически!)
        diffResult.dispatchUpdatesTo(this);
    }
}
