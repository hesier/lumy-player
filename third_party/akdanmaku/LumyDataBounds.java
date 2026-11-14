package com.kuaishou.akdanmaku;

import com.kuaishou.akdanmaku.data.DanmakuItem;
import java.util.List;

/** Slice indices use an inclusive time range and an exclusive subList end. */
public final class LumyDataBounds {
    private LumyDataBounds() {}

    public static int lowerBound(List<DanmakuItem> items, long timeMs) {
        return find(items, timeMs, false);
    }

    public static int upperBound(List<DanmakuItem> items, long timeMs) {
        return find(items, timeMs, true);
    }

    private static int find(List<DanmakuItem> items, long timeMs, boolean upper) {
        int low = 0;
        int high = items.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            long position = items.get(middle).getTimePosition();
            if (position < timeMs || (upper && position == timeMs)) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
