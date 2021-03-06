package io.metersphere.notice.service;

import io.metersphere.base.domain.ApiTestReport;
import io.metersphere.base.domain.LoadTestWithBLOBs;
import io.metersphere.base.domain.SystemParameter;
import io.metersphere.base.domain.TestCaseWithBLOBs;
import io.metersphere.commons.constants.APITestStatus;
import io.metersphere.commons.constants.NoticeConstants;
import io.metersphere.commons.constants.ParamConstants;
import io.metersphere.commons.constants.PerformanceTestStatus;
import io.metersphere.commons.user.SessionUser;
import io.metersphere.commons.utils.EncryptUtils;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.dto.BaseSystemConfigDTO;
import io.metersphere.i18n.Translator;
import io.metersphere.notice.domain.MessageDetail;
import io.metersphere.notice.domain.NoticeDetail;
import io.metersphere.notice.domain.UserDetail;
import io.metersphere.service.SystemParameterService;
import io.metersphere.service.UserService;
import io.metersphere.track.request.testcase.IssuesRequest;
import io.metersphere.track.request.testplan.AddTestPlanRequest;
import io.metersphere.track.request.testreview.SaveCommentRequest;
import io.metersphere.track.request.testreview.SaveTestCaseReviewRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MailService {
    @Resource
    private UserService userService;
    @Resource
    private SystemParameterService systemParameterService;

    public void sendPerformanceNotification(List<NoticeDetail> noticeList, String status, LoadTestWithBLOBs loadTest, String id) {
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        Map<String, String> context = new HashMap<>();
        context.put("title", "Performance" + Translator.get("timing_task_result_notification"));
        context.put("testName", loadTest.getName());
        context.put("id", id);
        context.put("type", "performance");
        context.put("url", baseSystemConfigDTO.getUrl());
        String performanceTemplate = "";
        try {
            if (StringUtils.equals(status, PerformanceTestStatus.Completed.name())) {
                performanceTemplate = IOUtils.toString(this.getClass().getResource("/mail/successPerformance.html"), StandardCharsets.UTF_8);
            } else if (StringUtils.equals(status, PerformanceTestStatus.Error.name())) {
                performanceTemplate = IOUtils.toString(this.getClass().getResource("/mail/failPerformance.html"), StandardCharsets.UTF_8);
            }
            sendHtmlTimeTasks(noticeList, status, context, performanceTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }


    public void sendApiNotification(ApiTestReport apiTestReport, List<NoticeDetail> noticeList) {
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        Map<String, String> context = new HashMap<>();
        context.put("title", "api" + Translator.get("timing_task_result_notification"));
        context.put("testName", apiTestReport.getName());
        context.put("type", "api");
        context.put("url", baseSystemConfigDTO.getUrl());
        context.put("id", apiTestReport.getId());
        String apiTemplate = "";
        try {
            if (StringUtils.equals(APITestStatus.Success.name(), apiTestReport.getStatus())) {
                apiTemplate = IOUtils.toString(this.getClass().getResource("/mail/success.html"), StandardCharsets.UTF_8);
            } else if (StringUtils.equals(APITestStatus.Error.name(), apiTestReport.getStatus())) {
                apiTemplate = IOUtils.toString(this.getClass().getResource("/mail/fail.html"), StandardCharsets.UTF_8);
            }
            sendHtmlTimeTasks(noticeList, apiTestReport.getStatus(), context, apiTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }

    //jenkins
    public void sendApiJenkinsNotification(String context, MessageDetail messageDetail) throws MessagingException {
        JavaMailSenderImpl javaMailSender = getMailSender();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(javaMailSender.getUsername());
        helper.setSubject("MeterSphere平台" + Translator.get("task_notification"));
        helper.setText(context);
        List<UserDetail> list = userService.queryTypeByIds(messageDetail.getUserIds());
        List<String> EmailList = new ArrayList<>();
        list.forEach(u -> {
            EmailList.add(u.getEmail());
        });
        helper.setTo(EmailList.toArray(new String[0]));
        try {
            javaMailSender.send(mimeMessage);
        } catch (MailException e) {
            LogUtil.error(e);
        }
    }

    private void sendHtmlTimeTasks(List<NoticeDetail> noticeList, String status, Map<String, String> context, String template) throws MessagingException {
        JavaMailSenderImpl javaMailSender = getMailSender();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(javaMailSender.getUsername());
        helper.setSubject(Translator.get("timing_task_result_notification"));
        helper.setText(getContent(template, context), true);
        helper.setTo(getRecipientEmail(noticeList, status));
        try {
            javaMailSender.send(mimeMessage);
        } catch (MailException e) {
            LogUtil.error(e);
        }
    }

    //测试评审

    public void sendEndNotice(MessageDetail messageDetail, List<String> userIds, SaveTestCaseReviewRequest reviewRequest, String eventType) {
        Map<String, String> context = getReviewContext(reviewRequest);
        try {
            String endTemplate = IOUtils.toString(this.getClass().getResource("/mail/end.html"), StandardCharsets.UTF_8);
            sendReviewNotice(addresseeIdList(messageDetail, userIds, eventType), context, endTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }


    public void sendDeleteNotice(MessageDetail messageDetail, List<String> userIds, SaveTestCaseReviewRequest reviewRequest, String eventType) {
        Map<String, String> context = getReviewContext(reviewRequest);
        try {
            String endTemplate = IOUtils.toString(this.getClass().getResource("/mail/deleteReview.html"), StandardCharsets.UTF_8);
            sendReviewNotice(addresseeIdList(messageDetail, userIds, eventType), context, endTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }


    public void sendCommentNotice(MessageDetail messageDetail, List<String> userIds, SaveCommentRequest request, TestCaseWithBLOBs testCaseWithBLOBs, String eventType) {
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        Map<String, String> context = new HashMap<>();
        context.put("maintainer", testCaseWithBLOBs.getMaintainer());
        context.put("testCaseName", testCaseWithBLOBs.getName());
        context.put("description", request.getDescription());
        context.put("url", baseSystemConfigDTO.getUrl());
        context.put("id", testCaseWithBLOBs.getId());
        try {
            String commentTemplate = IOUtils.toString(this.getClass().getResource("/mail/comment.html"), StandardCharsets.UTF_8);
            sendReviewNotice(addresseeIdList(messageDetail, userIds, eventType), context, commentTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }


    public void sendReviewerNotice(MessageDetail messageDetail, List<String> userIds, SaveTestCaseReviewRequest reviewRequest, String eventType) {
        Map<String, String> context = getReviewContext(reviewRequest);
        try {
            String reviewerTemplate = IOUtils.toString(this.getClass().getResource("/mail/reviewer.html"), StandardCharsets.UTF_8);
            sendReviewNotice(addresseeIdList(messageDetail, userIds, eventType), context, reviewerTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }

    //测试计划

    public void sendTestPlanStartNotice(MessageDetail messageDetail, List<String> userIds, AddTestPlanRequest testPlan, String eventType) {
        Map<String, String> context = getTestPlanContext(testPlan);
        context.put("creator", userIds.toString());
        try {
            String endTemplate = IOUtils.toString(this.getClass().getResource("/mail/testPlanStart.html"), StandardCharsets.UTF_8);
            sendTestPlanNotice(addresseeIdList(messageDetail, userIds, eventType), context, endTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }


    public void sendTestPlanEndNotice(MessageDetail messageDetail, List<String> userIds, AddTestPlanRequest testPlan, String eventType) {
        Map<String, String> context = getTestPlanContext(testPlan);
        context.put("creator", userIds.toString());
        try {
            String endTemplate = IOUtils.toString(this.getClass().getResource("/mail/testPlanEnd.html"), StandardCharsets.UTF_8);
            sendTestPlanNotice(addresseeIdList(messageDetail, userIds, eventType), context, endTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }


    public void sendTestPlanDeleteNotice(MessageDetail messageDetail, List<String> userIds, AddTestPlanRequest testPlan, String eventType) {
        Map<String, String> context = getTestPlanContext(testPlan);
        context.put("creator", userIds.toString());
        try {
            String endTemplate = IOUtils.toString(this.getClass().getResource("/mail/testPlanDelete.html"), StandardCharsets.UTF_8);
            sendTestPlanNotice(addresseeIdList(messageDetail, userIds, eventType), context, endTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }

    //缺陷任务
    public void sendIssuesNotice(MessageDetail messageDetail, List<String> userIds, IssuesRequest issuesRequest, String eventType, SessionUser user) {
        Map<String, String> context = new HashMap<>();
        context.put("issuesName", issuesRequest.getTitle());
        context.put("creator", user.getName());
        try {
            String endTemplate = IOUtils.toString(this.getClass().getResource("/mail/issuesCreate.html"), StandardCharsets.UTF_8);
            sendIssuesNotice(addresseeIdList(messageDetail, userIds, eventType), context, endTemplate);
        } catch (Exception e) {
            LogUtil.error(e);
        }
    }

    private Map<String, String> getReviewContext(SaveTestCaseReviewRequest reviewRequest) {
        Long startTime = reviewRequest.getCreateTime();
        Long endTime = reviewRequest.getEndTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = null;
        String sTime = String.valueOf(startTime);
        String eTime = String.valueOf(endTime);
        if (!sTime.equals("null")) {
            start = sdf.format(new Date(Long.parseLong(sTime)));
        }
        String end = null;
        if (!eTime.equals("null")) {
            end = sdf.format(new Date(Long.parseLong(eTime)));
        }

        Map<String, String> context = new HashMap<>();
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        context.put("url", baseSystemConfigDTO.getUrl());
        context.put("creator", reviewRequest.getCreator());
        context.put("reviewName", reviewRequest.getName());
        context.put("start", start);
        context.put("end", end);
        context.put("id", reviewRequest.getId());
        return context;
    }

    private Map<String, String> getTestPlanContext(AddTestPlanRequest testPlan) {
        Long startTime = testPlan.getPlannedStartTime();
        Long endTime = testPlan.getPlannedEndTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = null;
        String sTime = String.valueOf(startTime);
        String eTime = String.valueOf(endTime);
        if (!sTime.equals("null")) {
            start = sdf.format(new Date(Long.parseLong(sTime)));
        }
        String end = null;
        if (!eTime.equals("null")) {
            end = sdf.format(new Date(Long.parseLong(eTime)));
        }

        Map<String, String> context = new HashMap<>();
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        context.put("url", baseSystemConfigDTO.getUrl());
        context.put("testPlanName", testPlan.getName());
        context.put("start", start);
        context.put("end", end);
        context.put("id", testPlan.getId());
        return context;
    }

    private void sendReviewNotice(List<String> userIds, Map<String, String> context, String Template) throws MessagingException {
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }
        JavaMailSenderImpl javaMailSender = getMailSender();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(javaMailSender.getUsername());
        helper.setSubject("MeterSphere平台" + Translator.get("test_review_task_notice"));
        String[] users;
        List<String> emails = new ArrayList<>();
        List<UserDetail> list = userService.queryTypeByIds(userIds);
        list.forEach(u -> {
            emails.add(u.getEmail());
        });
        users = emails.toArray(new String[0]);
        helper.setText(getContent(Template, context), true);
        helper.setTo(users);
        if (users.length > 0) {
            javaMailSender.send(mimeMessage);
        }
    }

    private void sendTestPlanNotice(List<String> userIds, Map<String, String> context, String Template) throws MessagingException {
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }
        JavaMailSenderImpl javaMailSender = getMailSender();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(javaMailSender.getUsername());
        helper.setSubject("MeterSphere平台" + Translator.get("test_plan_notification"));
        String[] users;
        List<String> emails = new ArrayList<>();
        List<UserDetail> list = userService.queryTypeByIds(userIds);
        list.forEach(u -> {
            emails.add(u.getEmail());
        });
        users = emails.toArray(new String[0]);
        helper.setText(getContent(Template, context), true);
        helper.setTo(users);
        javaMailSender.send(mimeMessage);

    }

    private void sendIssuesNotice(List<String> userIds, Map<String, String> context, String Template) throws MessagingException {
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }
        JavaMailSenderImpl javaMailSender = getMailSender();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(javaMailSender.getUsername());
        helper.setSubject("MeterSphere平台" + Translator.get("task_defect_notification"));
        String[] users;
        List<String> emails = new ArrayList<>();
        List<UserDetail> list = userService.queryTypeByIds(userIds);
        list.forEach(u -> {
            emails.add(u.getEmail());
        });
        users = emails.toArray(new String[0]);
        helper.setText(getContent(Template, context), true);
        helper.setTo(users);
        javaMailSender.send(mimeMessage);

    }

    private JavaMailSenderImpl getMailSender() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        List<SystemParameter> paramList = systemParameterService.getParamList(ParamConstants.Classify.MAIL.getValue());
        javaMailSender.setDefaultEncoding("UTF-8");
        javaMailSender.setProtocol("smtps");
        for (SystemParameter p : paramList) {
            switch (p.getParamKey()) {
                case "smtp.host":
                    javaMailSender.setHost(p.getParamValue());
                    break;
                case "smtp.port":
                    javaMailSender.setPort(Integer.parseInt(p.getParamValue()));
                    break;
                case "smtp.account":
                    javaMailSender.setUsername(p.getParamValue());
                    break;
                case "smtp.password":
                    javaMailSender.setPassword(EncryptUtils.aesDecrypt(p.getParamValue()).toString());
                    break;
                default:
                    break;
            }
        }
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.connectiontimeout", "5000");
        javaMailSender.setJavaMailProperties(props);
        return javaMailSender;
    }

    private String getContent(String template, Map<String, String> context) {
        if (MapUtils.isNotEmpty(context)) {
            for (String k : context.keySet()) {
                if (StringUtils.isNotBlank(context.get(k))) {
                    template = RegExUtils.replaceAll(template, "\\$\\{" + k + "}", context.get(k));
                }
            }
        }
        return template;
    }

    private String[] getRecipientEmail(List<NoticeDetail> noticeList, String status) {
        String[] recipientEmails;
        List<String> successEmailList = new ArrayList<>();
        List<String> failEmailList = new ArrayList<>();
        if (noticeList.size() > 0) {
            for (NoticeDetail n : noticeList) {
                if (StringUtils.equals(n.getEnable(), "true") && StringUtils.equals(n.getEvent(), NoticeConstants.EXECUTE_SUCCESSFUL)) {
                    List<UserDetail> list = userService.queryTypeByIds(n.getUserIds());
                    list.forEach(u -> {
                        successEmailList.add(u.getEmail());
                    });
                }
                if (StringUtils.equals(n.getEnable(), "true") && StringUtils.equals(n.getEvent(), NoticeConstants.EXECUTE_FAILED)) {
                    List<UserDetail> list = userService.queryTypeByIds(n.getUserIds());
                    list.forEach(u -> {
                        failEmailList.add(u.getEmail());
                    });
                }
            }
        } else {
            LogUtil.error("Recipient information is empty");
        }

        if (StringUtils.equalsAny(status, PerformanceTestStatus.Completed.name(), APITestStatus.Success.name())) {
            recipientEmails = successEmailList.toArray(new String[0]);
        } else {
            recipientEmails = failEmailList.toArray(new String[0]);
        }
        return recipientEmails;
    }

    private List<String> addresseeIdList(MessageDetail messageDetail, List<String> userIds, String eventType) {
        List<String> addresseeIdList = new ArrayList<>();
        messageDetail.getEvents().forEach(e -> {
            if (StringUtils.equals(eventType, e)) {
                messageDetail.getUserIds().forEach(u -> {
                    if (!StringUtils.equals(NoticeConstants.EXECUTOR, u) && !StringUtils.equals(NoticeConstants.EXECUTOR, u) && !StringUtils.equals(NoticeConstants.MAINTAINER, u)) {
                        addresseeIdList.add(u);
                    }
                    if (StringUtils.equals(NoticeConstants.CREATE, eventType) && StringUtils.equals(NoticeConstants.EXECUTOR, u)) {
                        addresseeIdList.addAll(userIds);
                    }
                    if (StringUtils.equals(NoticeConstants.UPDATE, eventType) && StringUtils.equals(NoticeConstants.FOUNDER, u)) {
                        addresseeIdList.addAll(userIds);
                    }
                    if (StringUtils.equals(NoticeConstants.DELETE, eventType) && StringUtils.equals(NoticeConstants.FOUNDER, u)) {
                        addresseeIdList.addAll(userIds);
                    }
                    if (StringUtils.equals(NoticeConstants.COMMENT, eventType) && StringUtils.equals(NoticeConstants.MAINTAINER, u)) {
                        addresseeIdList.addAll(userIds);
                    }


                });
            }
        });
        return addresseeIdList;
    }
}





