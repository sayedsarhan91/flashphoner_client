/*
Copyright (c) 2011 Flashphoner
All rights reserved. This Code and the accompanying materials
are made available under the terms of the GNU Public License v2.0
which accompanies this distribution, and is available at
http://www.gnu.org/licenses/old-licenses/gpl-2.0.html

Contributors:
    Flashphoner - initial API and implementation

This code and accompanying materials also available under LGPL and MPL license for Flashphoner buyers. Other license versions by negatiation. Write us support@flashphoner.com with any questions.
*/
package com.flashphoner.phone_app;

import com.flashphoner.sdk.rtmp.*;
import com.flashphoner.sdk.sip.request_params.InfoParams;
import com.flashphoner.sdk.softphone.*;
import com.flashphoner.sdk.softphone.exception.CrossCallException;
import com.flashphoner.sdk.softphone.exception.LicenseRestictionException;
import com.flashphoner.sdk.softphone.exception.PortsBusyException;
import com.flashphoner.sdk.softphone.exception.SoftphoneException;
import com.wowza.wms.amf.AMFData;
import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.amf.AMFDataObj;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.client.IClient;
import com.wowza.wms.module.IModuleOnApp;
import com.wowza.wms.module.IModuleOnConnect;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.request.RequestFunction;
import gov.nist.javax.sip.SIPConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

/**
 * Main application class. Default wowza application: flashphoner_app used this class as base. See config <code>conf/flashphoner_app/Application.xml</code>.<br/>
 * FlashPhonerApp handles connects disconnects and serves commands invoked by flash-client.<br/>
 * Flash client uses <code>NetConnection.connect()</code>, <code>NetConnection.call()</code> client-methods.<br/>
 * PhoneApp supports one modes <i>flashphoner</i>.<br/>
 */
public class PhoneApp extends ModuleBase implements IModuleOnConnect, IModuleOnApp, IRtmpClientsCollectionSupport {

    /**
     * Wowza application name. If your use own application name, you should change it here
     */
    public static final String APPLICATION_NAME = "phone_app";

    /**
     * Application instance. Default instance used.
     */
    public static IApplicationInstance instance;

    /**
     * Application starts after receiving first RTMP connect.<br/>
     * It initializes sipAccountsPool during starting.
     *
     * @param appInst
     */
    public void onAppStart(IApplicationInstance appInst) {
        instance = appInst;
        Logger.logger.info(4, "Initializing sip accounts complete");
    }

    /**
     * Invokes by Wowza server when application has no connected users and timeout has been expired.
     *
     * @param appInst
     */
    public void onAppStop(IApplicationInstance appInst) {

    }

    /**
     * Invokes when client does <code>NetConnection.connect()</code>.<br/>
     * See Wowza documentation API for more info about IClient, RequestFunction, AMFDataList classes.<br/>
     * If mode is <i>flashphoner</i>, Flashphoner creates new internal softphone and register it on VoIP server if <code>register_required=true</code>.<br/>
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void onConnect(IClient client, RequestFunction requestFunction, AMFDataList params) {

        // If we are logged by token - we will not send user data to client app, because this is security violation
        boolean loggedByToken = false;
        Logger.logger.info("onConnect " + params);


        if (!isDefaultInstance(client)) {
            client.rejectConnection();
            return;
        }

        /**
         * Example: {Obj[]: app: "phone_app", flashVer: "WIN 11,0,1,152", swfUrl: "http://87.226.225.62/120/flashphoner_client/flashphoner_js_api.swf", tcUrl: "rtmp://87.226.225.62:1935/phone_app", fpad: false, capabilities: 239.0, audioCodecs: 3575.0, videoCodecs: 252.0, videoFunction: 1.0, pageUrl: "http://87.226.225.62/120/flashphoner_client/PhoneJS.html", objectEncoding: 0.0}
         */
        AMFDataObj obj2 = params.getObject(2);

        String swfUrl = obj2.getString("swfUrl");
        String pageUrl = obj2.getString("pageUrl");
        String allowDomainsString = ClientConfig.getInstance().getProperty("allow_domains");

        if (allowDomainsString != null && !"".equals(allowDomainsString)) {
            String[] allowDomains = allowDomainsString.split(",");
            Boolean isAllowDomain = false;
            for (String allowDomain : allowDomains) {
                int index = swfUrl.indexOf(allowDomain);
                if (index >= 0 && index < 7) {
                    isAllowDomain = true;
                }
            }
            if (!isAllowDomain) {
                Logger.logger.info("THIS DOMAIN IS NOT ALLOWED!!!");
                client.rejectConnection();
                client.setShutdownClient(true);
                return;
            }
        }
        String flashVer = obj2.getString("flashVer");
        String[] splat = flashVer.split("\\s");
        String os = splat[0];//WIN
        String version = splat[1];//11,0,1,152
        String[] splat2 = version.split(",");
        int majorMinorVersion = Integer.parseInt(splat2[0] + splat2[1]);//110
        Logger.logger.info("majorMinorVersion: " + majorMinorVersion);

        AMFDataObj obj = params.getObject(PARAM1);


        if (obj == null) {
            Logger.logger.info("Connect's parameters are NULL");
            client.rejectConnection();
            client.setShutdownClient(true);
            return;
        }

        int width = obj.getInt("width");
        int height = obj.getInt("height");
        String supportedResolutions = obj.getString("supportedResolutions");


        boolean regRequired = obj.getBoolean("registerRequired");

        IRtmpClient rtmpClient;
        String token = obj.getString("token");
        String outboundProxy;
        String domain = null;
        int port;

        String auto_login_url = ClientConfig.getInstance().getProperty("auto_login_url");
        String authenticationName = obj.getString("authenticationName");
        String login = obj.getString("login");
        String password = obj.getString("password");
        String visibleName = obj.getString("visibleName");

        if (login != null && password != null) {



            outboundProxy = obj.getString("outboundProxy");
            if (outboundProxy == null || "".equals(outboundProxy)) {
                client.rejectConnection();
                return;
            }

            domain = obj.getString("domain");
            if (domain == null || "".equals(domain)) {
                domain = outboundProxy;
                return;
            }
            try {
                port = Integer.parseInt(obj.getString("port"));
            } catch (NumberFormatException ex) {
                port = SIPConstants.DEFAULT_PORT;
            }
        } else {
            loggedByToken = true;

            if (auto_login_url == null) {
                Logger.logger.error("ERROR - Property auto_login_url - '" + auto_login_url + "' does not exits in flashphoner-client.properties");
                client.rejectConnection();
                client.setShutdownClient(true);
                return;
            }
            URL url;
            StringBuilder response = new StringBuilder();
            try {

                File file = new File(auto_login_url);
                BufferedReader bufferedReader;
                if (file.exists()) {
                    bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                } else {
                    url = new URL(auto_login_url + "?token=" + token + "&swfUrl=" + swfUrl + "&pageUrl=" + pageUrl);
                    URLConnection conn = url.openConnection();
                    bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream()));

                }

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    response.append(line);
                }
                bufferedReader.close();

                Logger.logger.info("response from auth server - " + response.toString());
            } catch (MalformedURLException e) {
                Logger.logger.error("ERROR - '" + auto_login_url + "' is wrong;" + e);
                client.rejectConnection();
                client.setShutdownClient(true);
                return;
            } catch (IOException e) {
                Logger.logger.error("ERROR - '" + auto_login_url + "' is wrong;" + e);
                client.rejectConnection();
                client.setShutdownClient(true);
                return;
            }
            Document dom;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder db = dbf.newDocumentBuilder();
                InputStream in = new ByteArrayInputStream(response.toString().getBytes("UTF-8"));
                dom = db.parse(in);
            } catch (Exception e) {
                Logger.logger.error("ERROR - '" + response.toString() + "' has wrong format;" + e);
                client.rejectConnection();
                client.setShutdownClient(true);
                return;
            }

            Element el = dom.getDocumentElement();

            String temp = el.getAttribute("registered");
            if (!(temp == null || "".equals(temp))) {
                regRequired = Boolean.parseBoolean(temp);
            }

            temp = el.getAttribute("authenticationName");
            if (!(temp == null || "".equals(temp))) {
                authenticationName = temp;
            }

            login = el.getAttribute("login");
            if (login == null || "".equals(login)) {
                Logger.logger.error("ERROR - '" + response.toString() + "' has wrong format;");
            }
            password = el.getAttribute("password");
            if (password == null || "".equals(password)) {
                Logger.logger.error("ERROR - '" + response.toString() + "' has wrong format;");
            }

            visibleName = el.getAttribute("visibleName");

            outboundProxy = el.getAttribute("outboundProxy");

            if (outboundProxy == null || "".equals(outboundProxy)) {
                Logger.logger.error("ERROR - '" + response.toString() + "' has wrong format;");
                client.rejectConnection();
                client.setShutdownClient(true);
                return;
            }

            String portString = el.getAttribute("port");
            if (portString == null || "".equals(portString)) {
                port = SIPConstants.DEFAULT_PORT;
            } else {
                try {
                    port = Integer.parseInt(portString);
                } catch (NumberFormatException ex) {
                    port = SIPConstants.DEFAULT_PORT;
                }
            }

            domain = el.getAttribute("domain");

        }
        Logger.logger.info("outboundProxy - " + outboundProxy);


        if (visibleName == null || "".equals(visibleName)) {
            visibleName = login;
        }


        RtmpClientConfig config = new RtmpClientConfig();
        config.setLogin(login);
        config.setAuthenticationName(authenticationName);
        config.setPassword(password);
        config.setDomain(domain);
        config.setOutboundProxy(outboundProxy);
        config.setPort(port);

        config.setVisibleName(visibleName);
        config.setRegRequired(regRequired);
        config.setApplicationName(APPLICATION_NAME);
        config.setWidth(width);
        config.setHeight(height);
        config.setSupportedResolutions(supportedResolutions);
        config.setMajorMinorPlayerVersion(majorMinorVersion);
        config.setSwfUrl(swfUrl);
        config.setPageUrl(pageUrl);

        Logger.logger.info(config.toString());

        rtmpClient = new RtmpClient(config, client);

        if (!loggedByToken) {
            AMFDataObj amfDataObj = new AMFDataObj();
            amfDataObj.put("login", config.getLogin());
            if (config.getAuthenticationName() != null) {
                amfDataObj.put("authenticationName", config.getAuthenticationName());
            }
            amfDataObj.put("password", config.getPassword());
            amfDataObj.put("domain", config.getDomain());
            amfDataObj.put("outboundProxy", config.getOutboundProxy());
            amfDataObj.put("port", config.getPort());
            amfDataObj.put("regRequired", regRequired);

            client.call("getUserData", null, amfDataObj);
        }

        getRtmpClients().add(rtmpClient);

        Logger.logger.info("getRtmpClients " + getRtmpClients());

        client.acceptConnection();

        client.call("getVersion", null, Config.getInstance().getVersion());

    }

    /**
     * This method invoked when flash-client disconnects from wowza server.<br/>
     * Flashphoner sends unregister request if it registered.
     *
     * @param client
     */
    public void onDisconnect(IClient client) {
        Logger.logger.info(4, "Disconnect client: " + client.getClientId());
        if (!isDefaultInstance(client)) {
            return;
        }

        IRtmpClient rtmpClientByClient = getRtmpClients().findByClient(client);
        if (rtmpClientByClient != null) {
            Logger.logger.info(4, "Shutdown RtmpClient: " + rtmpClientByClient.getRtmpClientConfig().getLogin());
            IRtmpClient rtmpClient = getRtmpClients().remove(rtmpClientByClient);
            ISoftphone softphone = rtmpClient.getSoftphone();
            if (softphone != null) {
                try {
                    softphone.release();
                } catch (Exception e) {
                    Logger.logger.error("Can not release softphone! Possible leak", e);
                }
            }
            //release sip account in pool
            Logger.logger.info(4, "Release sip account in pool: " + rtmpClient.getRtmpClientConfig().getLogin());
        }
    }

    /**
     * Flashphoner uses default application instance.<br/>
     * If you would like to connect with another instance, you should do this in the onConnect method:
     * <p>
     * <code>
     * if (!isDefaultInstance(client)) {
     * client.acceptConnection();
     * return;
     * }
     * </code>
     * </p>
     * Note, that this is security leak. Do not use this behavior in production.<br/>
     * isDefaultInstance method just checks if application instance which client trying to connect is default.
     *
     * @param client
     * @return
     */
    private boolean isDefaultInstance(IClient client) {
        Logger.logger.info(4, "checking instance " + client.getAppInstance().getName());
        return client.getAppInstance().getName().equals("_definst_");
    }

    /**
     * Invokes after the connect accepted
     *
     * @param iClient
     */
    public void onConnectAccept(IClient iClient) {

    }

    /**
     * Invokes after the connect rejected
     *
     * @param iClient
     */
    public void onConnectReject(IClient iClient) {

    }

    /**
     * Retrieves reference to collection of IRtmpClient instances
     *
     * @return IRtmpClientsCollection
     */
    public IRtmpClientsCollection getRtmpClients() {
        return RtmpClients.getInstance();
    }

    /**
     * Sends DTMF message, where PARAM1 is DTMF and PARAM2 is callId, which DTMF addressed.<br/>
     * See <code>NetConnection.call("sendDtmf")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void sendDtmf(IClient client, RequestFunction requestFunction, AMFDataList params) {
        String dtmf = params.getString(PARAM1);
        String callId = params.getString(PARAM2);
        Logger.logger.info(4, "Send DTMF: " + dtmf);
        if ((dtmf != null) && (dtmf.length() != 0)) {
            IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
            try {
                rtmpClient.getSoftphone().sendDtmf(callId, dtmf);
            } catch (SoftphoneException e) {
                Logger.logger.error("Can not send DTMF", e);
            }
        }
    }

    /**
     * Creates outgoing call using PARAM1 - visibleName, PARAM3 - isVideoCall.<br/>
     * See <code>NetConnection.call("call")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void call(IClient client, RequestFunction requestFunction, AMFDataList params) {
        Logger.logger.info(4, "call " + params);
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);

        String caller = rtmpClient.getRtmpClientConfig().getLogin();
        String callee = params.getString(PARAM1);
        String visibleName = params.getString(PARAM2);
        Boolean isVideoCall = params.getBoolean(PARAM3);
        String token = params.getString(PARAM4);
        AMFData inviteParametersObj = params.get(PARAM5);

        Map<String, String> inviteParameters = null;
        if (inviteParametersObj != null && (inviteParametersObj instanceof AMFDataObj)) {
            AMFDataObj inviteParametersDataObj = (AMFDataObj) inviteParametersObj;
            if (inviteParametersDataObj.size() > 0) {
                inviteParameters = new HashMap<String, String>();
                for (Object temp : inviteParametersDataObj.getKeys()) {
                    String key = (String) temp;
                    inviteParameters.put(key, inviteParametersDataObj.getString(key));
                }

            }
        }

        if (token != null && !"null".equals(token)) {
            String[] data = getCalleeByToken(token, rtmpClient.getRtmpClientConfig().getSwfUrl(), rtmpClient.getRtmpClientConfig().getPageUrl());
            callee = data[0];
            visibleName = rtmpClient.getRtmpClientConfig().getVisibleName();
        }
        if (callee == null || "".equals(callee)) {
            rtmpClient.fail(ErrorCodes.USER_NOT_AVAILABLE, null);
            return;
        }

        if ((visibleName == null) || (visibleName.length() == 0)) {
            visibleName = rtmpClient.getRtmpClientConfig().getLogin();
        }

        ISoftphoneCall call;

        try {

            call = rtmpClient.getSoftphone().call(caller, callee, visibleName, isVideoCall, inviteParameters);

        } catch (LicenseRestictionException e) {
            Logger.logger.info(4, e.getMessage());
            return;
        } catch (PortsBusyException e) {
            rtmpClient.fail(ErrorCodes.MEDIA_PORTS_BUSY, null);
            return;
        } catch (SoftphoneException e) {
            Throwable cause = e.getCause();
            if ((cause != null) && (cause instanceof CrossCallException)) {
                Logger.logger.info(4, "Cross call has been detected caller=" + caller + " callee=" + callee);
            } else {
                Logger.logger.error("Softphone error", e);
            }
            return;
        }

        ModuleBase.sendResult(client, params, call.toAMFDataObj());
    }

    private String[] getCalleeByToken(String token, String swfUrl, String pageUrl) {
        String getCalleUrl = ClientConfig.getInstance().getProperty("get_callee_url");
        if (getCalleUrl == null) {
            Logger.logger.error("ERROR - Property get_callee_url - '" + getCalleUrl + "' does not exits in flashphoner-client.properties");
            return null;
        }
        URL url;

        StringBuilder response = new StringBuilder();
        try {
            File file = new File(getCalleUrl);
            BufferedReader bufferedReader;
            if (file.exists()) {
                bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            } else {
                url = new URL(getCalleUrl + "?token=" + token + "&swfUrl=" + swfUrl + "&pageUrl=" + pageUrl);
                URLConnection conn = url.openConnection();
                // WSP-1667 - autologin not work. this paramteres imitate browswer request because some servers do not allow java make requests
                conn.addRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
                bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            }

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                response.append(line);
            }
            bufferedReader.close();
            Logger.logger.info("response from get_callee_url - " + response.toString());

            Document dom;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder db = dbf.newDocumentBuilder();
                InputStream in = new ByteArrayInputStream(response.toString().getBytes("UTF-8"));
                dom = db.parse(in);
            } catch (Exception e) {
                Logger.logger.error("ERROR - '" + response.toString() + "' has wrong format;" + e);
                return null;
            }

            Element el = dom.getDocumentElement();

            String[] data = new String[1];
            String temp = el.getAttribute("account");
            if (!(temp == null || "".equals(temp))) {
                data[0] = temp;
            }
            return data;
        } catch (MalformedURLException e) {
            Logger.logger.error("ERROR - '" + getCalleUrl + "' is wrong;" + e);
            return null;
        } catch (IOException e) {
            Logger.logger.error("ERROR - '" + getCalleUrl + "' is wrong;" + e);
            return null;
        }
    }

    /**
     * Answer incoming call, PARAM1 - callId, PARAM2 - isVideoCall.<br/>
     * See <code>NetConnection.call("answer")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void answer(IClient client, RequestFunction requestFunction, AMFDataList params) {
        Logger.logger.info(4, "answer " + params);
        String callId = params.getString(PARAM1);
        Boolean isVideoCall = params.getBoolean(PARAM2);
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
        try {
            rtmpClient.getSoftphone().answer(callId, isVideoCall);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not answer the call", e);
        }
    }

    /**
     * Updates call session to video.<br/>
     * PARAM1 - callId.<br/>
     * See <code>NetConnection.call("updateCallToVideo")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void updateCallToVideo(IClient client, RequestFunction requestFunction, AMFDataList params) {
        Logger.logger.info(4, "updateCallToVideo " + params);
        String callId = params.getString(PARAM1);
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
        try {
            rtmpClient.getSoftphone().updateCallToVideo(callId);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not update call to video", e);
        }
    }

    /**
     * Transfer call.<br/>
     * PARAM1 - callId.<br/>
     * PARAM2 - callee.<br/>
     * See <code>NetConnection.call("transfer")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void transfer(IClient client, RequestFunction requestFunction, AMFDataList params) {
        Logger.logger.info(4, "transfer " + params);
        String callId = params.getString(PARAM1);
        String callee = params.getString(PARAM2);
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
        try {
            rtmpClient.getSoftphone().transfer(callId, callee);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not transfer call", e);
        }
    }

    /**
     * Transfer call.<br/>
     * PARAM1 - callId.<br/>
     * PARAM2 - isHold.<br/>
     * See <code>NetConnection.call("hold")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void hold(IClient client, RequestFunction requestFunction, AMFDataList params) {
        Logger.logger.info(4, "hold " + params);
        String callId = params.getString(PARAM1);
        Boolean isHold = params.getBoolean(PARAM2);
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
        try {
            rtmpClient.getSoftphone().hold(callId, isHold);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not hold call", e);
        }
    }

    /**
     * Hangup call.<br/>
     * PARAM1 - callId.<br/>
     * See <code>NetConnection.call("hangup")</code> in flash-client.
     *
     * @param client
     * @param requestFunction
     * @param params
     */
    public void hangup(IClient client, RequestFunction requestFunction, AMFDataList params) {
        Logger.logger.info(4, "hangup " + params);
        String callId = params.getString(PARAM1);
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
        try {
            rtmpClient.getSoftphone().hangup(callId);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not hangup call", e);
        }
    }

    public void sendInstantMessage(IClient client, RequestFunction requestFunction, AMFDataList params) {
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);

        AMFDataObj obj = params.getObject(PARAM1);

        InstantMessage instantMessage = new InstantMessage();
        instantMessage.setBody(obj.getString("body"));
        instantMessage.setContentType(obj.getString("contentType"));
        instantMessage.setTo(obj.getString("to"));

        try {
            rtmpClient.getSoftphone().sendInstantMessage(instantMessage);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not send instant message", e);
        }
    }

    public void sendInfo(IClient client, RequestFunction requestFunction, AMFDataList params) {
        IRtmpClient rtmpClient = getRtmpClients().findByClient(client);
        AMFDataObj obj = params.getObject(PARAM1);
        InfoParams infoParams = new InfoParams(obj.getString("contentType"), obj.getString("body"), obj.getString("callId"));
        try {
            rtmpClient.getSoftphone().sendInfo(infoParams);
        } catch (SoftphoneException e) {
            Logger.logger.error("Can not send info", e);
        }
    }

}
