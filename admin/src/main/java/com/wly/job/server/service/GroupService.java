package com.wly.job.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.JobGroup;
import com.wly.job.server.dao.rep.GroupRep;
import com.wly.job.server.pojo.req.QueryGroupReq;
import com.wly.job.server.pojo.resp.GroupResp;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public record GroupService(GroupRep groupRep) {

    public PageResp<GroupResp> page(PageReq<QueryGroupReq> pageReq) {
        LambdaQueryWrapper<JobGroup> wrapper = Wrappers.<JobGroup>lambdaQuery().orderByDesc(JobGroup::getId);
        if (pageReq.getQuery() != null && StringUtils.hasText(pageReq.getQuery().getGroupName())) {
            wrapper.like(JobGroup::getName, pageReq.getQuery().getGroupName());
        }
        Page<JobGroup> page = groupRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);
        return PageResp.of(page.convert(JobBeanConverter::convert).getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
    }
}
