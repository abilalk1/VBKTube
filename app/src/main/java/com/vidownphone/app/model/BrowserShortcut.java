package com.vidownphone.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public class BrowserShortcut {

    private String id;
    private String title;
    private String url;
    private String subtitle;
    private String iconType;
    private boolean isRemovable;

    public BrowserShortcut(String id, String title, String url, String subtitle, String iconType, boolean isRemovable) {
        this.id = id;
        this.title = title;
        this.url = url;
        this.subtitle = subtitle;
        this.iconType = iconType;
        this.isRemovable = isRemovable;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("title", title);
            obj.put("url", url);
            obj.put("subtitle", subtitle);
            obj.put("iconType", iconType);
            obj.put("isRemovable", isRemovable);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static BrowserShortcut fromJson(JSONObject obj) {
        if (obj == null) return null;
        String id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
        String title = obj.optString("title", "Kısayol");
        String url = obj.optString("url", "https://");
        String subtitle = obj.optString("subtitle", "Web Sitesi");
        String iconType = obj.optString("iconType", "custom");
        boolean isRemovable = obj.optBoolean("isRemovable", true);
        return new BrowserShortcut(id, title, url, subtitle, iconType, isRemovable);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getIconType() {
        return iconType;
    }

    public void setIconType(String iconType) {
        this.iconType = iconType;
    }

    public boolean isRemovable() {
        return isRemovable;
    }

    public void setRemovable(boolean removable) {
        isRemovable = removable;
    }
}
