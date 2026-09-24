package com.simple_p2p.client.config;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 配置界面里的一行：值统一按字符串读写，具体控件由 {@link Type} 决定。
 */
public final class ConfigOption {

    public enum Type {
        /** 分组标题，不可编辑。 */
        HEADER,
        BOOL,
        INT,
        STRING,
        /** 在 choices 之间循环切换。 */
        ENUM,
        /** 按钮，点击执行 action。 */
        ACTION
    }

    public final String label;
    public final Type type;
    public final String[] choices;
    public final int min;
    public final int max;

    private final Supplier<String> getter;
    private final Consumer<String> setter;
    private final Runnable action;

    private ConfigOption(String label, Type type, String[] choices, int min, int max,
                         Supplier<String> getter, Consumer<String> setter, Runnable action) {
        this.label = label;
        this.type = type;
        this.choices = choices == null ? new String[0] : choices;
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
        this.action = action;
    }

    public static ConfigOption header(String label) {
        return new ConfigOption(label, Type.HEADER, null, 0, 0, () -> "", v -> {}, null);
    }

    public static ConfigOption bool(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        return new ConfigOption(label, Type.BOOL, null, 0, 0,
                () -> String.valueOf(Boolean.TRUE.equals(get.get())),
                v -> set.accept(Boolean.parseBoolean(v)), null);
    }

    public static ConfigOption integer(String label, int min, int max,
                                       Supplier<Integer> get, Consumer<Integer> set) {
        return new ConfigOption(label, Type.INT, null, min, max,
                () -> String.valueOf(get.get()),
                v -> {
                    try {
                        int n = Integer.parseInt(v.trim());
                        set.accept(Math.max(min, Math.min(max, n)));
                    } catch (NumberFormatException ignored) {
                    }
                }, null);
    }

    public static ConfigOption string(String label, Supplier<String> get, Consumer<String> set) {
        return new ConfigOption(label, Type.STRING, null, 0, 0,
                () -> {
                    String v = get.get();
                    return v == null ? "" : v;
                }, set, null);
    }

    public static ConfigOption choice(String label, String[] choices,
                                      Supplier<String> get, Consumer<String> set) {
        return new ConfigOption(label, Type.ENUM, choices, 0, 0,
                () -> {
                    String v = get.get();
                    return v == null ? choices[0] : v;
                }, set, null);
    }

    public static ConfigOption action(String label, Runnable action) {
        return new ConfigOption(label, Type.ACTION, null, 0, 0, () -> "", v -> {}, action);
    }

    public String value() {
        return getter.get();
    }

    public void set(String v) {
        setter.accept(v);
    }

    public void run() {
        if (action != null) action.run();
    }
}
