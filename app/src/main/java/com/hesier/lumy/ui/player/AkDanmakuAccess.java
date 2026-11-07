package com.hesier.lumy.ui.player;

import com.kuaishou.akdanmaku.LumyCacheBudget;
import com.kuaishou.akdanmaku.DanmakuConfig;
import com.kuaishou.akdanmaku.ecs.DanmakuEngine;
import com.kuaishou.akdanmaku.ecs.system.DataSystem;
import com.kuaishou.akdanmaku.data.DanmakuItem;
import com.kuaishou.akdanmaku.ui.DanmakuPlayer;
import java.lang.reflect.Field;
import java.util.List;

/** Version-pinned access to upstream JVM APIs; avoids reflection in the frame path. */
final class AkDanmakuAccess {
    // getConfig() 只返回待提交配置；首帧提交后须读取引擎当前配置。
    static DanmakuConfig config(DanmakuPlayer player) {
        DanmakuConfig pending = player.getConfig();
        return pending != null ? pending : player.getEngine$library_release().getContext$library_release().getConfig();
    }

    static Object cacheManager(DanmakuPlayer player) {
        return player.getEngine$library_release().getContext$library_release().getCacheManager();
    }

    static DataSystem dataSystem(DanmakuPlayer player) {
        return player.getEngine$library_release().getSystem(DataSystem.class);
    }

    @SuppressWarnings("unchecked")
    static void trimData(DanmakuPlayer player, long retainAfterMs) {
        DataSystem data = dataSystem(player);
        try {
            // Upstream has append but no removal API. Trim only expired input on window rollover,
            // on the serial worker; live entities and their lane assignments remain untouched.
            Field sortedField = DataSystem.class.getDeclaredField("sortedData");
            sortedField.setAccessible(true);
            List<DanmakuItem> sorted = (List<DanmakuItem>) sortedField.get(data);
            synchronized (data) {
                int expired = 0;
                while (expired < sorted.size() && sorted.get(expired).getData().getPosition() < retainAfterMs) expired++;
                sorted.subList(0, expired).clear();
                Field forceField = DataSystem.class.getDeclaredField("forceUpdate");
                forceField.setAccessible(true);
                forceField.setBoolean(data, true);
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported AkDanmaku data window API", error);
        }
    }

    static void advance(DanmakuPlayer player, boolean paused) {
        DanmakuEngine engine = player.getEngine$library_release();
        LumyCacheBudget.beginBatch(cacheManager(player));
        if (paused) {
            // Cache completion needs a new snapshot, not new lane assignments.
            // Seek/config changes already invalidate layout when it is actually needed.
            engine.getContext$library_release().getConfig().updateRender();
        }
        engine.step$library_release(null);
        engine.preAct$library_release();
        engine.act$library_release();
    }
}
