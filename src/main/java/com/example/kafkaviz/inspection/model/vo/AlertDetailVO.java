package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 告警详情:告警行 + 逐通道分发日志。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertDetailVO {

    private AlertRecordVO alert;

    /** 该告警被分发过的每个通道一条;未配通道时为空列表。 */
    private List<AlertDispatchLogVO> dispatchLogs;
}
