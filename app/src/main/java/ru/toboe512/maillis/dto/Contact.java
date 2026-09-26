package ru.toboe512.maillis.dto;


public class Contact {
    private final int id;
    private final String name;
    private final String email;
    private boolean isSelected; // Флаг для галочки (выбора в группу)

    public Contact(int id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.isSelected = false;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }
}