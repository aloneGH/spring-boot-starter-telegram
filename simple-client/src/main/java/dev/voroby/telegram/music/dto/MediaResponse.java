package dev.voroby.telegram.music.dto;

import java.util.ArrayList;
import java.util.List;

public class MediaResponse<T> {
    private boolean hasMore;

    private List<T> data;

    public MediaResponse() {
        hasMore = false;
        data = new ArrayList<>();
    }

    public MediaResponse(boolean hasMore, List<T> data) {
        this.hasMore = hasMore;
        this.data = data;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
    }
}
