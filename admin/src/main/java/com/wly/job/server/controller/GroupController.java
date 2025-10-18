package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.pojo.req.AddGroupReq;
import com.wly.job.server.pojo.req.QueryGroupReq;
import com.wly.job.server.pojo.resp.GroupResp;
import com.wly.job.server.service.GroupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author wly
 * @date 2021/11/23
 * 分组管理
 */
@RestController
@RequestMapping("/admin/group")
public record GroupController(GroupService groupService) {

    /**
     * 查询所有分组
     *
     * @return
     */
    @GetMapping("/all")
    public Result<List<GroupResp>> listAll() {
        return Result.success(groupService.groupRep().list().stream().map(JobBeanConverter::convert).toList());
    }

    /**
     * 添加分组
     *
     * @param req
     * @return
     */
    @PostMapping("/add")
    public Result<Void> add(@RequestBody AddGroupReq req) {
        groupService.groupRep().save(JobBeanConverter.convert(req));
        return Result.success();
    }

    /**
     * 分页查询分组列表
     *
     * @param pageReq
     * @return
     */
    @PostMapping("/page")
    public Result<PageResp<GroupResp>> page(PageReq<QueryGroupReq> pageReq) {
        return Result.success(groupService.page(pageReq));
    }

}
