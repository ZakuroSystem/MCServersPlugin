package jp.mcservers.connector;

final class AutoRegistration {
    private AutoRegistration() {
    }

    static boolean enabled(boolean configured) {
        return configured;
    }
}
