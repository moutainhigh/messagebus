/**
 * (C) Copyright 2016 Ymatou (http://www.ymatou.com/).
 *
 * All rights reserved.
 */
package com.ymatou.messagebus.facade.impl;

import java.util.Date;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;

import com.ymatou.messagebus.domain.model.Message;
import com.ymatou.messagebus.domain.service.MessageBusService;
import com.ymatou.messagebus.facade.PublishMessageFacade;
import com.ymatou.messagebus.facade.enums.MessageNewStatusEnum;
import com.ymatou.messagebus.facade.enums.MessageProcessStatusEnum;
import com.ymatou.messagebus.facade.enums.MessagePublishStatusEnum;
import com.ymatou.messagebus.facade.model.PublishMessageReq;
import com.ymatou.messagebus.facade.model.PublishMessageResp;
import com.ymatou.messagebus.infrastructure.net.NetUtil;
import com.ymatou.performancemonitorclient.PerformanceStatisticContainer;

/**
 * 发布消息接口实现
 * 
 * @author wangxudong 2016年7月27日 下午7:08:37
 *
 */
@Component("publishMessageFacade")
public class PublishMessageFacadeImpl implements PublishMessageFacade {

    @Resource
    private MessageBusService messageBusService;

    private String monitorAppId = "mqpublish.monitor.iapi.ymatou.com";

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.ymatou.messagebus.facade.PublishMessageFacade#publish(com.ymatou.messagebus.facade.model.
     * PublishMessageReq)
     */
    @Override
    public PublishMessageResp publish(PublishMessageReq req) {
        long startTime = System.currentTimeMillis();

        Message message = new Message();
        message.setAppId(req.getAppId());
        message.setBody(req.getBody());
        message.setCode(req.getCode());
        message.setIp(req.getIp());
        message.setCreateTime(new Date());
        message.setBusReceivedServerIp(NetUtil.getHostIp());
        message.setPushStatus(MessagePublishStatusEnum.AlreadyPush.code()); // 避免被.NET版补单
        message.setUuid(Message.newUuid());
        message.setMessageId(req.getMsgUniqueId());
        message.setNewStatus(MessageNewStatusEnum.InRabbitMQ.code());
        message.setProcessStatus(MessageProcessStatusEnum.Init.code());

        messageBusService.publish(message);

        PublishMessageResp resp = new PublishMessageResp();
        resp.setSuccess(true);
        resp.setUuid(message.getUuid());


        // 向性能监控器汇报性能情况
        long consumedTime = System.currentTimeMillis() - startTime;
        PerformanceStatisticContainer.addAsync(consumedTime, message.getAppCode(), monitorAppId);
        PerformanceStatisticContainer.addAsync(consumedTime, "Total", monitorAppId);

        return resp;
    }

}
