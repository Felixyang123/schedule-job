package com.wly.job.common.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户会话 DTO：承载管控后台当前登录用户的最小信息（userId / username），
 * 由 Admin 端过滤器或切面构建后写入 {@link UserSessionContext} 供后续访问。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    private String userId;

    private String username;

}
