package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.vo.TicketCollaboratorVO;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketEvaluationVO;
import com.bluemalic.repair.vo.TicketLogVO;
import com.bluemalic.repair.vo.TicketVO;

import java.util.List;
import java.util.Map;

/**
 * Ticket 实体与 VO 的转换。名称类字段（楼栋/类别）由调用方批量查出后以 Map 传入，
 * 避免逐条回库（N+1）。
 */
public class TicketConverter {

    private TicketConverter() {
    }

    public static TicketVO toVO(Ticket ticket, Map<Long, String> buildingNames, Map<Long, String> categoryNames) {
        TicketVO vo = new TicketVO();
        copyBaseFields(vo, ticket, buildingNames, categoryNames);
        return vo;
    }

    /**
     * 两个 VO 共有的父类字段。此前 toVO 与 toDetailVO 各拷一份，注释自己写着"字段新增时这里是
     * 必须同步的点"——同步点从两处收敛到一处，新增字段只改这里。
     */
    private static void copyBaseFields(TicketVO vo, Ticket ticket,
                                       Map<Long, String> buildingNames, Map<Long, String> categoryNames) {
        vo.setId(ticket.getId());
        vo.setTicketNo(ticket.getTicketNo());
        vo.setStatus(ticket.getStatus());
        vo.setUrgency(ticket.getUrgency());
        vo.setBuildingId(ticket.getBuildingId());
        vo.setBuildingName(buildingNames.get(ticket.getBuildingId()));
        vo.setRoom(ticket.getRoom());
        vo.setCategoryId(ticket.getCategoryId());
        vo.setCategoryName(categoryNames.get(ticket.getCategoryId()));
        vo.setStudentId(ticket.getStudentId());
        vo.setWorkerId(ticket.getWorkerId());
        vo.setSubmitTime(ticket.getSubmitTime());
        vo.setDispatchTime(ticket.getDispatchTime());
        vo.setFinishTime(ticket.getFinishTime());
        vo.setArriveMinutes(ticket.getArriveMinutes());
        vo.setHandleMinutes(ticket.getHandleMinutes());
    }

    public static TicketDetailVO toDetailVO(Ticket ticket,
                                            Map<Long, String> buildingNames,
                                            Map<Long, String> categoryNames,
                                            List<TicketCollaboratorVO> collaborators,
                                            String parentTicketNo,
                                            List<TicketLogVO> logs,
                                            TicketEvaluation evaluation) {
        TicketDetailVO vo = new TicketDetailVO();
        copyBaseFields(vo, ticket, buildingNames, categoryNames);
        // 详情扩展
        vo.setDescription(ticket.getDescription());
        vo.setImages(ticket.getImages());
        vo.setResultDesc(ticket.getResultDesc());
        vo.setResultImages(ticket.getResultImages());
        vo.setRejectReason(ticket.getRejectReason());
        vo.setAcceptTime(ticket.getAcceptTime());
        vo.setArriveTime(ticket.getArriveTime());
        vo.setCloseTime(ticket.getCloseTime());
        vo.setCollaborators(collaborators);
        vo.setParentTicketId(ticket.getParentTicketId());
        vo.setParentTicketNo(parentTicketNo);
        vo.setLogs(logs);
        if (evaluation != null) {
            TicketEvaluationVO evaluationVO = new TicketEvaluationVO();
            evaluationVO.setId(evaluation.getId());
            evaluationVO.setScore(evaluation.getScore());
            evaluationVO.setContent(evaluation.getContent());
            evaluationVO.setCreateTime(evaluation.getCreateTime());
            vo.setEvaluation(evaluationVO);
        }
        return vo;
    }

    public static TicketLogVO toLogVO(TicketLog log, Map<Long, String> operatorNames) {
        TicketLogVO vo = new TicketLogVO();
        vo.setId(log.getId());
        vo.setAction(log.getAction());
        vo.setFromStatus(log.getFromStatus());
        vo.setToStatus(log.getToStatus());
        vo.setOperatorId(log.getOperatorId());
        vo.setOperatorName(operatorNames.get(log.getOperatorId()));
        vo.setRemark(log.getRemark());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
