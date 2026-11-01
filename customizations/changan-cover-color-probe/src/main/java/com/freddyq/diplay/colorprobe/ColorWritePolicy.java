package com.freddyq.diplay.colorprobe;

/** Fixed current-color property only. No arbitrary property/value control surface. */
final class ColorWritePolicy {
    static final int COLOR_PROPERTY = 0x314003a6;
    static final int AREA = 0;
    static int validate(int target, Integer original) {
        if (original == null || original < 0 || original > 255) throw new IllegalStateException("尚未读取原色");
        // 4/48 come from the OEM's own seasonal color branch; no guessed RGB/color-number mapping.
        if (target != 4 && target != 48 && target != original) throw new IllegalArgumentException("只允许本次测试色号或恢复原色");
        return target;
    }
}
