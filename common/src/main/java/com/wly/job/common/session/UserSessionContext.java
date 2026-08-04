package com.wly.job.common.session;

import java.util.Optional;

/**
 * 用户会话上下文：基于 InheritableThreadLocal 的进程内线程上下文，
 * 提供当前登录用户会话的存取与判断能力（登录态、userId、username）。
 * <p>
 * 注意：使用方须在请求处理完成后调用 {@link #clear()} 清理，避免线程池复用导致会话串线。
 */
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
