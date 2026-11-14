import com.kuaishou.akdanmaku.data.DanmakuItem;
import com.kuaishou.akdanmaku.ecs.system.DataSystem;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Runs the bundled engine's real slice method with timestamp-only host items. */
public final class DataSliceTest {
    public static void main(String[] args) throws Exception {
        checkWindowTail();
        checkBoundaries();
        System.out.println("PASS: window tail, append, empty/single slices, duplicate/exact bounds and 500 random slices");
    }

    private static void checkWindowTail() throws Exception {
        List<DanmakuItem> items = new ArrayList<>();
        for (long time = 1_000; time <= 56_000; time += 1_000) items.add(new DanmakuItem(time));
        for (long now : new long[] {50_017, 56_017, 59_999}) {
            checkSlice(items, now - 8_000, now + 8_000);
        }
        for (long time = 61_000; time <= 120_000; time += 1_000) items.add(new DanmakuItem(time));
        checkSlice(items, 52_017, 68_017);
    }

    private static void checkBoundaries() throws Exception {
        checkSlice(List.of(), 0, 8_000);
        checkSlice(List.of(new DanmakuItem(1_000)), 0, 8_000);
        List<DanmakuItem> repeated = List.of(new DanmakuItem(1_000), new DanmakuItem(1_000),
                new DanmakuItem(2_000), new DanmakuItem(3_000), new DanmakuItem(3_000));
        checkSlice(repeated, 1_000, 3_000);
        checkSlice(repeated, 1_001, 2_999);
        checkSlice(repeated, 2_000, 2_000);
        checkSlice(repeated, 3_001, 8_000);
        checkSlice(repeated, -8_000, 999);
        Random random = new Random(56_000);
        for (int trial = 0; trial < 500; trial++) {
            List<DanmakuItem> items = new ArrayList<>();
            long time = 0;
            for (int i = random.nextInt(80); i > 0; i--) {
                time += random.nextInt(5);
                items.add(new DanmakuItem(time));
            }
            long start = random.nextInt(250) - 10;
            checkSlice(items, start, start + random.nextInt(100));
        }
    }

    private static void checkSlice(List<DanmakuItem> items, long start, long end) throws Exception {
        List<DanmakuItem> expected = new ArrayList<>();
        for (DanmakuItem item : items) {
            if (item.getTimePosition() >= start && item.getTimePosition() <= end) expected.add(item);
        }
        List<?> actual = slice(items, start, end);
        if (!expected.equals(actual)) {
            throw new AssertionError("Slice [" + start + ", " + end + "] expected=" + expected + " actual=" + actual);
        }
    }

    private static List<?> slice(List<DanmakuItem> items, long start, long end) throws Exception {
        // Bypass Android worker construction; execute updateCurrentSlice itself unchanged.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object system = ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(DataSystem.class);
        put(system, "sortedData", items);
        put(system, "pendingCreateItems", new ArrayList<>());
        Class<?> type = Class.forName("com.kuaishou.akdanmaku.ecs.system.Danmakus");
        Constructor<?> constructor = type.getDeclaredConstructor(List.class, long.class, long.class,
                int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        put(system, "currentData", constructor.newInstance(new ArrayList<>(), 0L, 0L, 0, 0, false));
        put(system, "startTimeMills", start);
        put(system, "endTimeMills", end);
        Method update = DataSystem.class.getDeclaredMethod("updateCurrentSlice");
        update.setAccessible(true);
        update.invoke(system);
        Field pending = DataSystem.class.getDeclaredField("pendingCreateItems");
        pending.setAccessible(true);
        return (List<?>) pending.get(system);
    }

    private static void put(Object system, String name, Object value) throws Exception {
        Field field = DataSystem.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(system, value);
    }
}
