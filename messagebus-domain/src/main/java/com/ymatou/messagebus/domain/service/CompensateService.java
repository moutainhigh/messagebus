/**
 * (C) Copyright 2016 Ymatou (http://www.ymatou.com/).
 *
 * All rights reserved.
 */
package com.ymatou.messagebus.domain.service;

import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import com.ymatou.messagebus.domain.model.*;
import com.ymatou.messagebus.domain.repository.AppConfigRepository;
import com.ymatou.messagebus.domain.repository.MessageCompensateRepository;
import com.ymatou.messagebus.domain.repository.MessageRepository;
import com.ymatou.messagebus.domain.repository.MessageStatusRepository;
import com.ymatou.messagebus.facade.BizException;
import com.ymatou.messagebus.facade.ErrorCode;
import com.ymatou.messagebus.facade.enums.MessageCompensateSourceEnum;
import com.ymatou.messagebus.facade.enums.MessageNewStatusEnum;
import com.ymatou.messagebus.facade.enums.MessageProcessStatusEnum;
import com.ymatou.messagebus.facade.enums.MessageStatusEnum;

/**
 * 补单服务
 * 
 * @author tony 2016年8月14日 下午4:08:35
 *
 */
@Component
public class CompensateService implements InitializingBean {

    private static Logger logger = LoggerFactory.getLogger(CompensateService.class);

    private CloseableHttpAsyncClient httpClient;

    @Resource
    private MessageCompensateRepository messageCompensateRepository;

    @Resource
    private MessageRepository messageRepository;

    @Resource
    private MessageStatusRepository messageStatusRepository;

    @Resource
    private AppConfigRepository appConfigRepository;

    @Resource
    private CallbackServiceImpl callbackServiceImpl;

    @Resource
    private TaskExecutor taskExecutor;

    /**
     * 检测补单合并逻辑，供调度使用
     */
    public void checkAndCompensate() {
        List<AppConfig> allAppConfig = appConfigRepository.getAllAppConfig();
        for (AppConfig appConfig : allAppConfig) {
            if (!StringUtils.isEmpty(appConfig.getDispatchGroup())) {
                for (MessageConfig messageConfig : appConfig.getMessageCfgList()) {
                    if (!Boolean.FALSE.equals(messageConfig.getEnable())) {

                        String appId = appConfig.getAppId();
                        String code = messageConfig.getCode();

                        logger.info("check and compensate Start, appId:{}, code:{}.", appId, code);

                        // 如果关闭日志，就不需要检测进补单
                        if (messageConfig.isNeedCheckCompensate()) {
                            logger.info("STEP.1 checkToCompensate");
                            try {
                                checkToCompensate(appId, code);
                            } catch (Exception e) {
                                logger.error("STEP.1 checkToCompensate fail.", e);
                            }
                        }

                        logger.info("STEP.2 compensate");
                        try {
                            compensate(appId, code);
                        } catch (Exception e) {
                            logger.error("STEP.2 compensate fail.", e);
                        }

                        logger.info("check and compensate end.");
                    }
                }
            }
        }
    }


    public void checkAndCompensate(String appId, MessageConfig messageConfig) {
        if (!Boolean.FALSE.equals(messageConfig.getEnable())) {
            String code = messageConfig.getCode();
            logger.info("check and compensate Start, appId:{}, code:{}.", appId, code);

            // 如果关闭日志，就不需要检测进补单
            if (messageConfig.isNeedCheckCompensate()) {
                logger.info("STEP.1 checkToCompensate");
                try {
                    checkToCompensate(appId, code);
                } catch (Exception e) {
                    logger.error("STEP.1 checkToCompensate fail.", e);
                }
            }

            logger.info("STEP.2 compensate");
            try {
                compensate(appId, code);
            } catch (Exception e) {
                logger.error("STEP.2 compensate fail.", e);
            }

            logger.info("check and compensate end.");

        }
    }

    /**
     * 检测出需要补偿的消息写入补单库
     */
    public void checkToCompensate(String appId, String code) {
        AppConfig appConfig = appConfigRepository.getAppConfig(appId);
        if (appConfig == null) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid appId:" + appId);
        }

        MessageConfig messageConfig = appConfig.getMessageConfig(code);
        if (messageConfig == null) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid code:" + code);
        }

        if (messageConfig.getCallbackCfgList() == null || messageConfig.getCallbackCfgList().size() == 0) {
            throw new BizException(ErrorCode.NOT_EXIST_INVALID_CALLBACK,
                    String.format("appid:%s, code:%s", appId, code));
        }

        List<Message> needToCompensate = messageRepository.getNeedToCompensate(appId, code,
                messageConfig.getCheckCompensateDelay(), messageConfig.getCheckCompensateTimeSpan());
        int needToCompensateNum = 0;
        if (needToCompensate != null) {
            needToCompensateNum = needToCompensate.size();
        }
        logger.info(
                String.format("check need to compensate,appId:%s, code:%s, num:%d", appId, code, needToCompensateNum));

        if (needToCompensate != null && needToCompensate.size() > 0) {
            logger.error("check need to compensate,appId:{}, code:{}, num:{}", appId, code, needToCompensate.size());

            for (Message message : needToCompensate) {
                MessageStatus messageStatus = null;
                for (CallbackConfig callbackConfig : messageConfig.getCallbackCfgList()) {
                    messageStatus = messageStatusRepository.getByUuid(appId, message.getUuid(),
                            callbackConfig.getCallbackKey());
                    if (messageStatus != null) { // 如果状态表中已经有记录了,说明这条消息已经被分发过,不需要再进入补单了
                        break;
                    }

                    MessageCompensate messageCompensate =
                            MessageCompensate.from(message, callbackConfig, MessageCompensateSourceEnum.Compensate);
                    messageCompensateRepository.insert(messageCompensate);
                }

                if (messageStatus == null) {
                    messageRepository.updateMessageStatus(appId, code, message.getUuid(),
                            MessageNewStatusEnum.CheckToCompensate, MessageProcessStatusEnum.Init);
                } else {
                    if (MessageStatusEnum.PushOk.toString().equals(messageStatus.getStatus())) {
                        messageRepository.updateMessageStatus(appId, code, message.getUuid(),
                                MessageNewStatusEnum.InRabbitMQ, MessageProcessStatusEnum.Success);
                    } else {
                        messageRepository.updateMessageStatus(appId, code, message.getUuid(),
                                MessageNewStatusEnum.DispatchToCompensate, MessageProcessStatusEnum.Compensate);
                    }
                }
            }
        }
    }

    /**
     * 根据Appid和Code进行补单
     */
    public void compensate(String appId, String code) {
        AppConfig appConfig = appConfigRepository.getAppConfig(appId);
        if (appConfig == null) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid appId:" + appId);
        }

        MessageConfig messageConfig = appConfig.getMessageConfig(code);
        if (messageConfig == null) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid code:" + code);
        }


        List<MessageCompensate> messageCompensatesList = messageCompensateRepository.getNeedCompensate(appId, code);

        int needToCompensateNum = 0;
        if (messageCompensatesList != null) {
            needToCompensateNum = messageCompensatesList.size();
        }
        logger.info(
                String.format("find need to compensate,appId:%s, code:%s, num:%d", appId, code, needToCompensateNum));

        if (messageCompensatesList.size() > 0) {
            logger.info("find need to compensate,appId:{}, code:{}, num:{}", appId, code,
                    messageCompensatesList.size());
            for (MessageCompensate messageCompensate : messageCompensatesList) {
                try {
                    compensateCallback(messageCompensate, messageConfig);
                } catch (Exception e) {
                    logger.error(String.format("message compensate failed with appId:%s, code:%s, messageId:%s.", appId,
                            code, messageCompensate.getMessageId()), e);
                }
            }
        }
    }

    /**
     * 秒级补单
     * 
     * @param message
     */
    public void secondCompensate(Message message, String consumerId, int timeSpanSecond) {
        String requestId = MDC.get("logPrefix");

        taskExecutor.execute(() -> {
            MDC.put("logPrefix", requestId);

            logger.info("----------------------- second compensate begin ----------------");
            try {
                AppConfig appConfig = appConfigRepository.getAppConfig(message.getAppId());
                if (appConfig == null) {
                    throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid appId:" + message.getAppId());
                }

                MessageConfig messageConfig = appConfig.getMessageConfig(message.getCode());
                if (messageConfig == null) {
                    throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid code:" + message.getCode());
                }

                CallbackConfig callbackConfig = messageConfig.getCallbackConfig(consumerId);
                if (callbackConfig == null) {
                    throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid consumerId:" + consumerId);
                }

                new BizSystemCallback(httpClient, message, null, callbackConfig, callbackServiceImpl)
                        .secondCompensate(timeSpanSecond);

            } catch (Exception e) {
                logger.error("secondCompensate fail.", e);
            }
            logger.info("----------------------- second compensate end ----------------");
        });
    }

    /**
     * 补单回调
     * 
     * @param messageCompensate
     */
    private void compensateCallback(MessageCompensate messageCompensate, MessageConfig messageConfig) {
        CallbackConfig callbackConfig = messageConfig.getCallbackConfig(messageCompensate.getConsumerId());

        if (callbackConfig == null) {
            throw new RuntimeException("can not find callbackconfig");
        }

        // 直接从补单消息转换来，避免因为消息库丢失造成的补单失败
        // Message message = messageRepository.getByUuid(appId, code, uuid);
        Message message = Message.from(messageCompensate);

        try {
            new BizSystemCallback(httpClient, message, messageCompensate, callbackConfig, callbackServiceImpl).send();
        } catch (Exception e) {
            logger.error(String.format("compensate call biz system fail,appCode:%s, messageUuid:%s",
                    message.getAppCode(), message.getUuid()), e);
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor();
        PoolingNHttpClientConnectionManager cm = new PoolingNHttpClientConnectionManager(ioReactor);
        cm.setDefaultMaxPerRoute(20);
        cm.setMaxTotal(100);

        RequestConfig defaultRequestConfig = RequestConfig.custom()
                .setSocketTimeout(5000)
                .setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .build();

        httpClient = HttpAsyncClients.custom().setDefaultRequestConfig(defaultRequestConfig)
                .setConnectionManager(cm).build();
        httpClient.start();
    }
}
