package dev.voroby.telegram.music.cache;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Cache {
    public static final List<TdApi.ChatFolderInfo> chatFolders = Collections.synchronizedList(new ArrayList<>());
}
