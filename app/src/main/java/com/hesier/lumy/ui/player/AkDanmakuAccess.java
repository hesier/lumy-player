package com.hesier.lumy.ui.player;

import com.kuaishou.akdanmaku.LumyCacheBudget;
import com.kuaishou.akdanmaku.DanmakuConfig;
import com.kuaishou.akdanmaku.ecs.DanmakuEngine;
import com.kuaishou.akdanmaku.ui.DanmakuPlayer;

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

    static void advance(DanmakuPlayer player, boolean paused) {
        DanmakuEngine engine = player.getEngine$library_release();
        LumyCacheBudget.beginBatch(cacheManager(player));
        if (paused) {
            // 上游重排先清 visibility；必须同时清占位，避免 retainer 复用 false 隐藏已有弹幕。
            engine.getContext$library_release().getConfig().updateRetainer();
            engine.getContext$library_release().getConfig().updateLayout();
            engine.getContext$library_release().getConfig().updateRender();
        }
        engine.step$library_release(null);
        engine.preAct$library_release();
        engine.act$library_release();
    }
}
