package com.bluemalic.repair.common;

/**
 * 工单流转动作，与 ticket_log.action 的取值一一对应。
 */
public enum TicketAction {

    SUBMIT("提交报修"),
    DISPATCH("派单"),
    ACCEPT("接单"),
    ARRIVE("到场打卡"),
    FINISH("完工上报"),
    EVALUATE("验收评价"),
    REWORK("验收不通过"),
    TRANSFER("转派"),
    ADD_COLLABORATOR("加协作者"),
    REMOVE_COLLABORATOR("移除协作者"),
    SPLIT("拆单"),
    CANCEL("撤销工单"),
    CLOSE("关闭工单"),
    REJECT("驳回"),
    AUTO_CLOSE("超时自动关闭"),
    ACCEPT_TIMEOUT("接单超时提醒"),
    PROCESS_TIMEOUT("处理超时升级");

    private final String desc;

    TicketAction(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
