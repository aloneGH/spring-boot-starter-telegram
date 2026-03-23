package dev.voroby.telegram.music.dto;

import java.util.Objects;

public class ArtistItem {
    private long id;

    private String name;

    public ArtistItem() {
    }

    public ArtistItem(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "ArtistItem{" +
                "id=" + id +
                ", name='" + name + '\'' +
                '}';
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof ArtistItem that)) return false;

        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
