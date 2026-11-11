package com.bluemalic.repair.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 报修受理 Agent 的一轮对话。 */
@Data
public class AgentIntakeDTO {

    /** 会话标识。不传（或格式不合法）时服务端新建一个并在响应里回传。 */
    @Size(max = 64, message = "会话标识过长")
    private String sessionId;

    /** 用户这一轮说的话。 */
    @NotBlank(message = "请描述一下问题")
    @Size(max = 500, message = "一次最多说 500 字")
    private String message;

    /**
     * 可选的位置上下文：扫房门上报修码得到的 6 位码。
     *
     * <p>**由客户端传进来，而不是让模型自己去查**：报修码是防枚举面（靠限流挡暴力猜码，
     * ADR-009），把它做成工具等于让猜码绕过那一层（ADR-017 的工具白名单判断标准）。
     */
    @Size(max = 32, message = "报修码格式不正确")
    private String repairCode;
}
