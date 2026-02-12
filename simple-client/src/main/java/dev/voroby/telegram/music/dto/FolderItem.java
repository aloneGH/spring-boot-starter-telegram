package dev.voroby.telegram.music.dto;


public class FolderItem {
    public long folderId;

    public String folderName;

    public FolderItem() {
    }

    public FolderItem(long folderId, String folderName) {
        this.folderId = folderId;
        this.folderName = folderName;
    }

    public long getFolderId() {
        return folderId;
    }

    public void setFolderId(long folderId) {
        this.folderId = folderId;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    @Override
    public String toString() {
        return "FolderItem{" +
                "folderId=" + folderId +
                ", folderName='" + folderName + '\'' +
                '}';
    }
}
