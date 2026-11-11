package com.wly.job.server.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.wly.job.server.dao.entity.AdminSession;
import com.wly.job.server.dao.mapper.AdminSessionMapper;
import com.wly.sso.core.bean.LoginInfo;
import com.wly.sso.core.storage.LoginStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component("defaultLoginStorage")
@RequiredArgsConstructor
public class DatabaseLoginStorage implements LoginStorage {

    private final AdminSessionMapper mapper;

    @Override
    public void save(LoginInfo loginInfo) {
        AdminSession row = toRow(loginInfo);
        if (mapper.selectByAccountId(row.getAccountId()) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    @Override
    public LoginInfo get(String accountId) {
        AdminSession row = mapper.selectByAccountId(accountId);
        if (row == null) {
            return null;
        }
        if (row.getExpireTime() == null || !row.getExpireTime().after(new Date())) {
            mapper.deleteByAccountId(accountId);
            return null;
        }
        return fromRow(row);
    }

    @Override
    public void update(LoginInfo loginInfo) {
        mapper.updateById(toRow(loginInfo));
    }

    @Override
    public void remove(String accountId) {
        if (accountId != null) {
            mapper.deleteByAccountId(accountId);
        }
    }

    @Override
    public List<LoginInfo> all() {
        return mapper.selectList(null).stream()
                .filter(row -> row.getExpireTime() != null && row.getExpireTime().after(new Date()))
                .map(this::fromRow)
                .toList();
    }

    private AdminSession toRow(LoginInfo info) {
        Date now = new Date();
        return AdminSession.builder()
                .accountId(info.getAccountId())
                .accountName(info.getAccountName())
                .signature(info.getSignature())
                .expireTime(info.getExpireTime() == null ? now : new Date(info.getExpireTime()))
                .rolesJson(JSON.toJSONString(info.getRoles() == null ? Collections.emptyMap() : info.getRoles()))
                .permissionsJson(JSON.toJSONString(info.getPermissions() == null ? Collections.emptyMap() : info.getPermissions()))
                .createTime(now)
                .updateTime(now)
                .build();
    }

    private LoginInfo fromRow(AdminSession row) {
        return LoginInfo.builder()
                .accountId(row.getAccountId())
                .accountName(row.getAccountName())
                .signature(row.getSignature())
                .expireTime(row.getExpireTime().getTime())
                .roles(parseMap(row.getRolesJson()))
                .permissions(parseMap(row.getPermissionsJson()))
                .build();
    }

    private Map<String, List<String>> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return JSON.parseObject(json, new TypeReference<>() {});
    }
}
