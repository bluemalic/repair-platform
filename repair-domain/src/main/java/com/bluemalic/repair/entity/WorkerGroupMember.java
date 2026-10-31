package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 维修工所属工种（多对多）。解除关系就是删行，所以没有 updateTime / deleted。 */
@Data
@TableName("worker_group_member")
public class WorkerGroupMember {

    private Long id;

    private Long tenantId;

    private Long workerId;

    private Long groupId;

    private LocalDateTime createTime;
}
