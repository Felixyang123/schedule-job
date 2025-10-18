package com.wly.job.common.session;

import java.util.Optional;

public class UserSessionContext {
    private static final InheritableThreadLocal<UserSession> userSession = new InheritableThreadLocal<>();

    public static void setUserSession(UserSession session) {
        userSession.set(session);
    }

    public static UserSession getUserSession() {
        return userSession.get();
    }

    public static void clear() {
        userSession.remove();
    }

    public static boolean isLogin() {
        return userSession.get() != null;
    }

    public static String getUserId() {
        return Optional.ofNullable(userSession.get()).map(UserSession::getUserId).orElse(null);
    }

    public static String getUserName() {
        return Optional.ofNullable(userSession.get()).map(UserSession::getUsername).orElse(null);
    }
}
