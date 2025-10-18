package com.wly.job.server.controller;

import com.wly.job.common.bean.Result;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.rep.GroupRep;
import com.wly.job.server.pojo.req.AddGroupReq;
import com.wly.job.server.pojo.resp.GroupResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author wly
 * @date 2021/11/23
 * 分组管理
 */
@RestController
@RequestMapping("/admin/group")
@RequiredArgsConstructor
public class GroupController {
    private final GroupRep groupRep;

    /**
     * 查询所有分组
     * @return
     */
    @GetMapping("/all")
    public Result<List<GroupResp>> listAll() {
        return Result.success(groupRep.list().stream().map(JobBeanConverter::convert).toList());
    }

    /**
     * 添加分组
     * @param req
     * @return
     */
    @PostMapping("/add")
    public Result<Void> add(@RequestBody AddGroupReq req) {
        groupRep.save(JobBeanConverter.convert(req));
        return Result.success();
    }

}
