import com.simple_p2p.SimpleP2PMod;

/**
 * 独立运行入口（非Minecraft环境）
 * 用法：编译后运行 java -jar SimpleP2P.jar 或 java Main
 * 可以在命令行中测试 /p2p open /p2p mode 等命令
 * Minecraft 环境下由 SimpleP2PMod 内的各平台 @Mod 入口自动初始化
 */
public class Main {
    public static void main(String[] args) {
        SimpleP2PMod.main(args);
    }
}
