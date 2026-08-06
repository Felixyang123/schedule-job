package com.wly.job.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.JobGroup;
import com.wly.job.server.dao.rep.GroupRep;
import com.wly.job.server.pojo.req.AddGroupReq;
import com.wly.job.server.pojo.req.QueryGroupReq;
import com.wly.job.server.pojo.resp.GroupResp;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 作业分组服务（record 形式，无状态服务）。
 *
 * <p>负责作业分组（job_group 表）的查询与新增：分页查询支持按分组名模糊过滤；
 * 列表查询/新增供管控后台分组管理使用。分组用于组织执行器与作业，
 * 例如按业务线划分不同执行器组。
 */
@Service
public record GroupService(GroupRep groupRep) {

    /**
     * 查询全部分组（按 ID 升序，供分组下拉列表）。
     *
     * @return 作业分组列表
     */
    public List<GroupResp> listAll() {
        return groupRep.list().stream().map(JobBeanConverter::convert).toList();
    }

    /**
     * 新增分组。
     *
     * @param req 新增分组请求
     */
    public void add(AddGroupReq req) {
        groupRep.save(JobBeanConverter.convert(req));
    }

    /**
     * 分页查询作业分组（按 ID 倒序，支持分组名模糊匹配）。
     *
     * @param pageReq 分页请求与查询条件
     * @return 作业分组分页结果
     */
    public PageResp<GroupResp> page(PageReq<QueryGroupReq> pageReq) {
        LambdaQueryWrapper<JobGroup> wrapper = Wrappers.<JobGroup>lambdaQuery().orderByDesc(JobGroup::getId);
        if (pageReq.getQuery() != null && StringUtils.hasText(pageReq.getQuery().getGroupName())) {
            wrapper.like(JobGroup::getName, pageReq.getQuery().getGroupName());
        }
        Page<JobGroup> page = groupRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);
        return PageResp.of(page.convert(JobBeanConverter::convert).getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
    }
}
